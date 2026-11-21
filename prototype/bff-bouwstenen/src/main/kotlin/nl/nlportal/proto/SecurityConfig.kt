package nl.nlportal.proto

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeReactiveAuthenticationManager
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.session.ReactiveSessionRepository
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.time.Clock

/** Access- en refreshtokens worden niet bewaard: de BFF gebruikt ze alleen bij de code-uitwisseling. */
object DiscardingAuthorizedClientRepository : ServerOAuth2AuthorizedClientRepository {
    override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication,
        exchange: ServerWebExchange,
    ): Mono<T> = Mono.empty()

    override fun saveAuthorizedClient(
        authorizedClient: OAuth2AuthorizedClient,
        principal: Authentication,
        exchange: ServerWebExchange,
    ): Mono<Void> = Mono.empty()

    override fun removeAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication,
        exchange: ServerWebExchange,
    ): Mono<Void> = Mono.empty()
}

/** `jti` van een client-assertion: 32 bytes uit `SecureRandom`, base64url (256 bit). */
object AssertionIds {
    private val random = java.security.SecureRandom()

    fun next(): String = ByteArray(32).also { random.nextBytes(it) }.let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

const val SESSION_TENANT = "nlp.tenant"
const val SESSION_CREATED_AT = "nlp.createdAt"

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(ProtoProperties::class)
class SecurityConfig {
    @Bean
    fun tenantRegistry(p: ProtoProperties) = TenantRegistry(p)

    @Bean
    fun clientRegistrations(registry: TenantRegistry) = TenantClientRegistrationRepository(registry)

    @Bean
    fun preAuthRepository(redis: ReactiveStringRedisTemplate, p: ProtoProperties) =
        RedisPreAuthRepository(redis, p.flowCookieMaxAge)

    @Bean
    fun oidcSessionRegistry(redis: ReactiveStringRedisTemplate, p: ProtoProperties) =
        RedisOidcSessionRegistry(redis, p.absoluteSessionTimeout)

    @Bean
    fun securityChain(
        http: ServerHttpSecurity,
        registrations: TenantClientRegistrationRepository,
        preAuth: RedisPreAuthRepository,
        clock: Clock,
        oidcSessions: RedisOidcSessionRegistry,
        sessionRepository: ReactiveSessionRepository<*>,
        redis: ReactiveStringRedisTemplate,
    ): SecurityWebFilterChain {
        val tokenClient = WebClientReactiveAuthorizationCodeTokenResponseClient().apply {
            val assertion = NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest> { reg ->
                registrations.keyFor(reg)
            }
            // RFC 10070 §4: aud is de issuer van de AS (enige waarde); NL GOV: jti minstens 128 bit, nooit hergebruikt
            assertion.setJwtClientAssertionCustomizer { ctx ->
                ctx.headers.type("client-authentication+jwt")
                ctx.claims.audience(listOf(checkNotNull(ctx.authorizationGrantRequest.clientRegistration.providerDetails.issuerUri)))
                ctx.claims.id(AssertionIds.next())
            }
            addParametersConverter(
                Converter<OAuth2AuthorizationCodeGrantRequest, MultiValueMap<String, String>> { assertion.convert(it)!! },
            )
        }
        val authenticationManager = OidcAuthorizationCodeReactiveAuthenticationManager(
            tokenClient,
            OidcReactiveOAuth2UserService(),
        )
        return http
            .csrf { it.disable() } // buiten dit prototype (CSRF-ticket)
            .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .authorizeExchange {
                it.pathMatchers("/logout/connect/back-channel/*").permitAll().anyExchange().authenticated()
            }
            .oauth2Login {
                it.clientRegistrationRepository(registrations)
                    .authorizationRequestRepository(preAuth)
                    .authorizationRequestResolver(
                        ReturnToAuthorizationRequestResolver(DefaultServerOAuth2AuthorizationRequestResolver(registrations)),
                    )
                    .authenticationManager(authenticationManager)
                    .authorizedClientRepository(DiscardingAuthorizedClientRepository)
                    .authenticationSuccessHandler(bounceToFirstPage(clock))
                    .authenticationFailureHandler(failureHandler())
                    .oidcSessionRegistry(oidcSessions)
            }
            .logout {
                // een sequentiele handler: register leegmaken en dan de sessie ongeldig maken (die neemt de context mee)
                it.logoutHandler(
                    ServerLogoutHandler { wfe, _ ->
                        wfe.exchange.session.flatMap { s ->
                            oidcSessions.removeSessionInformation(s.id).then(s.invalidate())
                        }
                    },
                ).logoutSuccessHandler(
                    OidcClientInitiatedServerLogoutSuccessHandler(registrations).apply { setPostLogoutRedirectUri("{baseUrl}/") },
                )
            }
            .oidcLogout {
                it.clientRegistrationRepository(registrations).oidcSessionRegistry(oidcSessions).backChannel { b ->
                    b.logoutUri("/logout/connect/back-channel/{registrationId}")
                        .logoutHandler(BackChannelLogoutHandler(oidcSessions, sessionRepository, redis, clock))
                }
            }
            .build()
    }

    /**
     * De callback is een cross-site navigatie: een Strict-cookie wordt op een redirect in die keten niet meegestuurd.
     * Daarom een 200-pagina met een meta-refresh: de volgende navigatie start vanaf onze eigen pagina (same-site).
     */
    private fun bounceToFirstPage(clock: Clock) = ServerAuthenticationSuccessHandler { wfe, _ ->
        val exchange = wfe.exchange
        val target = exchange.getAttribute<String>(RETURN_TO_ATTRIBUTE) ?: "/"
        exchange.session.flatMap { session ->
            session.attributes[SESSION_TENANT] = exchange.tenant.id
            session.attributes[SESSION_CREATED_AT] = clock.instant()
            // het pad is al gecontroleerd (ReturnToAuthorizationRequestResolver), maar het gaat toch geescaped in HTML
            val safe = org.springframework.web.util.HtmlUtils.htmlEscape(target)
            val html = "<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url=$safe\">" +
                "<title>Even geduld</title><a href=\"$safe\">Doorgaan</a>"
            exchange.response.headers.contentType = MediaType.TEXT_HTML
            exchange.response.headers.cacheControl = "no-store"
            exchange.response.headers.set("Referrer-Policy", "no-referrer")
            exchange.response.headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            exchange.response.writeWith(
                Mono.just(exchange.response.bufferFactory().wrap(html.toByteArray(StandardCharsets.UTF_8))),
            )
        }
    }

    private fun failureHandler() = ServerAuthenticationFailureHandler { wfe, ex ->
        org.slf4j.LoggerFactory.getLogger("nlp.login").warn("login geweigerd: tenant={} fout={}", wfe.exchange.tenantOrNull?.id, ex.javaClass.simpleName)
        wfe.exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        wfe.exchange.response.setComplete()
    }
}
