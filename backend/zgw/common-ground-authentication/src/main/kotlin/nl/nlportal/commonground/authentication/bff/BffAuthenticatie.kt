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

import com.nimbusds.jose.jwk.RSAKey
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.portal.authentication.domain.Identiteit
import org.slf4j.LoggerFactory
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeReactiveAuthenticationManager
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.HtmlUtils
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Een geweigerde login met een vaste reden; de melding bevat nooit tokeninhoud. */
class LoginGeweigerdException(
    val reden: Reden,
    val actor: Actor?,
) : OAuth2AuthenticationException(OAuth2Error("login_geweigerd"), "Login geweigerd (${reden.code})")

/** De ingelogde gebruiker na de callback; draagt de beslissing van de [Inlogbeslisser] naar de sessieopslag. */
class BffOidcUser(
    val identiteit: Identiteit,
    idToken: OidcIdToken,
) : DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken, "sub")

/** `jti` van een client-assertion: 32 bytes uit `SecureRandom`, base64url (256 bit); NL GOV eist minstens 128 bit. */
object AssertionIds {
    private val random = SecureRandom()

    fun volgende(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
}

/**
 * De code-uitwisseling met `private_key_jwt` en de sleutel van de tenant. `aud` is de issuer van de registratie als enige
 * waarde (RFC 10070 par. 4), `typ` is `client-authentication+jwt` en `jti` heeft minstens 256 bit; de Spring-standaard
 * (`aud` is het token-endpoint, `jti` een UUID) volstaat niet. Nooit een `client_secret`.
 */
fun tokenClient(registraties: TenantClientRegistraties): WebClientReactiveAuthorizationCodeTokenResponseClient =
    WebClientReactiveAuthorizationCodeTokenResponseClient().apply {
        val assertion = NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest> { registratie -> registraties.sleutelVoor(registratie) as RSAKey }
        assertion.setJwtClientAssertionCustomizer { context ->
            context.headers.type("client-authentication+jwt")
            context.claims.audience(listOf(checkNotNull(context.authorizationGrantRequest.clientRegistration.providerDetails.issuerUri)))
            context.claims.id(AssertionIds.volgende())
        }
        addParametersConverter(
            Converter<OAuth2AuthorizationCodeGrantRequest, MultiValueMap<String, String>> { checkNotNull(assertion.convert(it)) },
        )
    }

/**
 * Spring valideert de code-uitwisseling en het ID-token (handtekening, `iss`, `aud`, `exp`, `nonce`); daarna beslist de
 * [Inlogbeslisser] over inlogmiddel, niveau, vertegenwoordiging en BSN. Een weigering is een [LoginGeweigerdException]; bij een
 * toestemming wordt de principal een [BffOidcUser] met de identiteit.
 */
class BffAuthenticationManager(
    private val delegate: ReactiveAuthenticationManager,
    private val tenants: TenantDirectory,
    private val beslisser: Inlogbeslisser,
) : ReactiveAuthenticationManager {
    override fun authenticate(authentication: Authentication): Mono<Authentication> =
        delegate.authenticate(authentication).flatMap { resultaat ->
            val login = resultaat as OAuth2LoginAuthenticationToken
            val oidcUser = login.principal as org.springframework.security.oauth2.core.oidc.user.OidcUser
            TenantContext.currentMono().flatMap<Authentication> { oin ->
                val tenant = tenants.forOin(oin) ?: return@flatMap Mono.error(LoginGeweigerdException(Reden.TENANT, null))
                if (login.clientRegistration.registrationId != oin.value) return@flatMap Mono.error(LoginGeweigerdException(Reden.TENANT, null))
                val loginStart =
                    login.authorizationExchange.authorizationRequest
                        .getAttribute<String>(BffNamen.LOGIN_START)
                        ?.toLongOrNull()
                        ?.let(Instant::ofEpochMilli)
                when (val beslissing = beslisser.beslis(tenant, oidcUser.idToken, loginStart)) {
                    is Inlogbeslissing.Geweigerd -> {
                        Mono.error(LoginGeweigerdException(beslissing.reden, beslissing.actor))
                    }

                    is Inlogbeslissing.Toegestaan -> {
                        Mono.just(
                            OAuth2LoginAuthenticationToken(
                                login.clientRegistration,
                                login.authorizationExchange,
                                BffOidcUser(beslissing.identiteit, oidcUser.idToken),
                                login.authorities,
                                checkNotNull(login.accessToken),
                                login.refreshToken,
                            ),
                        )
                    }
                }
            }
        }

    companion object {
        fun voor(
            registraties: TenantClientRegistraties,
            tenants: TenantDirectory,
        ) = BffAuthenticationManager(
            OidcAuthorizationCodeReactiveAuthenticationManager(tokenClient(registraties), OidcReactiveOAuth2UserService()),
            tenants,
            Inlogbeslisser(),
        )
    }
}

/**
 * Na een geslaagde callback: de sessie is al opgeslagen (nieuw sessie-ID, tenant, tijdstippen). De callback is een cross-site
 * navigatie, waarop een `Strict`-cookie niet meegaat; daarom een 200-pagina met `meta refresh` naar het return-pad, zodat de
 * volgende navigatie van onze eigen pagina uitgaat (same-site). Het pad is al gecontroleerd en gaat toch geëscaped in de HTML.
 */
class InlogSuccesHandler(
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : ServerAuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        webFilterExchange: WebFilterExchange,
        authentication: Authentication,
    ): Mono<Void> {
        val exchange = webFilterExchange.exchange
        val doel = exchange.getAttribute<String>(BffNamen.EXCHANGE_RETURN_TO)?.takeIf { BffNamen.VEILIG_PAD.matches(it) } ?: "/"
        val user = (authentication as? org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken)?.principal as? BffOidcUser
        return TenantContext.currentMono().flatMap { oin ->
            gebeurtenissen.registreer(
                Beveiligingsgebeurtenis(
                    GebeurtenisType.LOGIN_GESLAAGD,
                    oin.value,
                    user?.identiteit?.gebruiker?.let { Actor(it.issuer, it.subject) },
                    "geslaagd",
                ),
            )
            val veilig = HtmlUtils.htmlEscape(doel)
            val html = "<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url=$veilig\"><title>Even geduld</title><a href=\"$veilig\">Doorgaan</a>"
            exchange.response.headers.contentType = MediaType.TEXT_HTML
            exchange.response.headers.cacheControl = "no-store"
            exchange.response.headers.set("Referrer-Policy", "no-referrer")
            exchange.response.headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(html.toByteArray(StandardCharsets.UTF_8))))
        }
    }
}

/**
 * Een mislukte of geweigerde login is een securityevent (zonder `code`, `state` of tokeninhoud) en brengt de burger terug naar
 * het portaal met een vaste foutaanduiding; het flowcookie is al verlopen.
 */
class InlogFalenHandler(
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : ServerAuthenticationFailureHandler {
    override fun onAuthenticationFailure(
        webFilterExchange: WebFilterExchange,
        exception: org.springframework.security.core.AuthenticationException,
    ): Mono<Void> =
        TenantContext.currentMono().flatMap { oin ->
            val geweigerd = exception as? LoginGeweigerdException
            gebeurtenissen.registreer(Beveiligingsgebeurtenis(GebeurtenisType.LOGIN_GEWEIGERD, oin.value, geweigerd?.actor, "geweigerd", geweigerd?.reden ?: Reden.PROTOCOL))
            val response = webFilterExchange.exchange.response
            response.statusCode = HttpStatus.FOUND
            response.headers.location = URI.create("/?inloggen=mislukt")
            response.headers.cacheControl = "no-store"
            response.setComplete()
        }
}

/**
 * Uitloggen (alleen `POST`, CSRF-beschermd): de portaalsessie is direct weg en de burger gaat naar het
 * `end_session_endpoint` van de realm met `id_token_hint` en een exacte `post_logout_redirect_uri` (RP-initiated logout), zodat
 * ook de Keycloak-sessie eindigt (DigiD-eis).
 */
class UitlogHandler(
    private val registraties: TenantClientRegistraties,
    private val tenants: TenantDirectory,
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : ServerLogoutHandler,
    ServerLogoutSuccessHandler {
    override fun logout(
        exchange: WebFilterExchange,
        authentication: Authentication,
    ): Mono<Void> {
        val webExchange = exchange.exchange
        return TenantContext.currentMono().flatMap { oin ->
            webExchange.session.flatMap { session ->
                val opgeslagen = session.getAttribute<String>(SessieAttributen.IDENTITEIT)?.let { OpgeslagenIdentiteit.parse(it) }
                if (opgeslagen != null) {
                    // Een fout bij het bouwen van de IdP-URL (ontbrekende registratie, kapot eindpunt) mag het beëindigen van de sessie nooit tegenhouden.
                    runCatching { bouwUrl(webExchange, oin.value, opgeslagen.idToken) }
                        .onFailure { log.warn("Uitlog-URL niet te bouwen voor tenant {}: {}", oin.value, it.javaClass.simpleName) }
                        .getOrNull()
                        ?.let { webExchange.attributes[BffNamen.EXCHANGE_UITLOG_URL] = it }
                    gebeurtenissen.registreer(Beveiligingsgebeurtenis(GebeurtenisType.UITGELOGD, oin.value, Actor(opgeslagen.issuer, opgeslagen.subject), "geslaagd"))
                }
                session.attributes.clear()
                session.invalidate()
            }
        }
    }

    override fun onLogoutSuccess(
        exchange: WebFilterExchange,
        authentication: Authentication,
    ): Mono<Void> =
        // Spring roept dit op bij het samenstellen van de keten, voor [logout] draait: pas bij het uitvoeren lezen we de uitlog-URL.
        Mono.defer {
            val response = exchange.exchange.response
            response.statusCode = HttpStatus.FOUND
            response.headers.location = exchange.exchange.getAttribute<URI>(BffNamen.EXCHANGE_UITLOG_URL) ?: URI.create("/")
            response.headers.cacheControl = "no-store"
            response.setComplete()
        }

    private fun bouwUrl(
        exchange: ServerWebExchange,
        oin: String,
        idToken: String,
    ): URI? {
        val tenant =
            tenants.forOin(
                nl.nlportal.core.tenancy
                    .Oin(oin),
            ) ?: return null
        val registratie = registraties.voor(tenant.oin)
        val eindpunt =
            registratie.providerDetails.configurationMetadata["end_session_endpoint"]
                ?.toString()
                ?.takeIf(::isAbsolutHttpUrl) ?: return null
        return UriComponentsBuilder
            .fromUriString(eindpunt)
            .queryParam("id_token_hint", idToken)
            .queryParam("client_id", registratie.clientId)
            .queryParam("post_logout_redirect_uri", TenantOrigin.van(exchange, tenant) + "/")
            .encode()
            .build()
            .toUri()
    }

    private fun isAbsolutHttpUrl(waarde: String): Boolean = runCatching { URI.create(waarde.trim()) }.getOrNull()?.let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() } == true

    private companion object {
        val log = LoggerFactory.getLogger(UitlogHandler::class.java)
    }
}