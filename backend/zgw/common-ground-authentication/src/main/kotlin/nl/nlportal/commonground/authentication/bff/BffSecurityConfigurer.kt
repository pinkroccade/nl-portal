/*
 * Copyright 2026 PinkRoccade Local Government B.V.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.security.config.HttpSecurityConfigurer
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantContextMissingException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.security.web.server.csrf.CsrfWebFilter
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/** Access- en refreshtokens worden niet bewaard: de BFF gebruikt ze alleen bij de code-uitwisseling. */
object WeggooiendeAuthorizedClientRepository : ServerOAuth2AuthorizedClientRepository {
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

/**
 * Zet het CSRF-cookie bij het eerste request al neer (Spring laadt het token anders pas als het wordt gelezen), zodat de SPA
 * het bij haar eerste muterende aanroep heeft. De token-requesthandler is de gewone (niet-XOR-)handler: de SPA leest het cookie
 * en stuurt het terug in `X-XSRF-TOKEN` (double submit); het cookie is `__Host-`, `Secure`, `Path=/` en `SameSite=Strict`.
 */
class CsrfCookieFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val token = exchange.getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name)
        return token?.then(chain.filter(exchange)) ?: chain.filter(exchange)
    }
}

/**
 * Het enige authenticatiepad van NL Portal (ADR 0022): `oauth2Login` per host-tenant met `private_key_jwt`, de sessie in Redis,
 * CSRF met de Origin-regel, uitloggen via `POST`, en een 401 (geen redirect) voor wie niet is ingelogd. Er is geen
 * resource-server en geen Bearer-pad.
 */
class BffSecurityConfigurer(
    private val registraties: TenantClientRegistraties,
    private val preAuth: PreAutorisatieRepository,
    private val startResolver: InlogStartResolver,
    private val manager: BffAuthenticationManager,
    private val contextRepository: SessieSecurityContextRepository,
    private val succes: InlogSuccesHandler,
    private val falen: InlogFalenHandler,
    private val uitlog: UitlogHandler,
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : HttpSecurityConfigurer {
    override fun configure(http: ServerHttpSecurity) {
        val csrfRepository =
            CookieServerCsrfTokenRepository.withHttpOnlyFalse().apply {
                setCookieName(BffNamen.CSRF_COOKIE)
                setCookiePath("/")
                setCookieCustomizer { it.secure(true).sameSite("Strict") }
            }
        val webhook = ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, BffNamen.POSTSALE_WEBHOOK)
        http
            .securityContextRepository(contextRepository)
            .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .csrf { csrf ->
                csrf
                    .csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(ServerCsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(AndServerWebExchangeMatcher(CsrfWebFilter.DEFAULT_CSRF_MATCHER, NegatedServerWebExchangeMatcher(webhook)))
                    .accessDeniedHandler { exchange, _ ->
                        TenantContext
                            .currentMono()
                            .doOnNext { gebeurtenissen.registreer(Beveiligingsgebeurtenis(GebeurtenisType.CSRF_GEWEIGERD, it.value, null, "geweigerd", Reden.CSRF)) }
                            // de managementpoort heeft geen tenant: daar is het alleen een 403
                            .onErrorResume(TenantContextMissingException::class.java) { Mono.empty() }
                            .then(
                                Mono.defer {
                                    exchange.response.statusCode = HttpStatus.FORBIDDEN
                                    exchange.response.setComplete()
                                },
                            )
                    }
            }.addFilterAfter(CsrfCookieFilter(), SecurityWebFiltersOrder.CSRF)
            .oauth2Login {
                it
                    .clientRegistrationRepository(registraties)
                    .authorizationRequestRepository(preAuth)
                    .authorizationRequestResolver(startResolver)
                    .authenticationManager(manager)
                    .authorizedClientRepository(WeggooiendeAuthorizedClientRepository)
                    .securityContextRepository(contextRepository)
                    .authenticationSuccessHandler(succes)
                    .authenticationFailureHandler(falen)
            }.logout {
                it
                    .requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, BffNamen.UITLOGPAD))
                    .logoutHandler { handlers ->
                        // de standaardhandlers (sessie ongeldig maken) draaien anders voor ons en wissen het ID-token dat we nodig hebben
                        handlers.clear()
                        handlers.add(uitlog)
                    }.logoutSuccessHandler(uitlog)
            }
    }
}