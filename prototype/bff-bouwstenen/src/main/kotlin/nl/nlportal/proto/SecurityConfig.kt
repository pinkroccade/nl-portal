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
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
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
    fun securityChain(
        http: ServerHttpSecurity,
        registrations: TenantClientRegistrationRepository,
        preAuth: RedisPreAuthRepository,
        clock: Clock,
    ): SecurityWebFilterChain {
        val tokenClient = WebClientReactiveAuthorizationCodeTokenResponseClient().apply {
            val assertion = NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest> { reg ->
                registrations.keyFor(reg)
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
            .authorizeExchange { it.anyExchange().authenticated() }
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
            val html = "<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url=$target\">" +
                "<title>Even geduld</title>"
            exchange.response.headers.contentType = MediaType.TEXT_HTML
            exchange.response.headers.cacheControl = "no-store"
            exchange.response.headers.set("Referrer-Policy", "no-referrer")
            exchange.response.writeWith(
                Mono.just(exchange.response.bufferFactory().wrap(html.toByteArray(StandardCharsets.UTF_8))),
            )
        }
    }

    private fun failureHandler() = ServerAuthenticationFailureHandler { wfe, ex ->
        org.slf4j.LoggerFactory.getLogger("nlp.login").warn("login geweigerd: {} query={}", ex.toString(), wfe.exchange.request.uri.rawQuery)
        wfe.exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        wfe.exchange.response.setComplete()
    }
}
