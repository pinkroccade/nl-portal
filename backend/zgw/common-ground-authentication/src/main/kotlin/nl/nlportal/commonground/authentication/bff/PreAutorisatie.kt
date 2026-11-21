package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.tenancy.TenantContext
import org.springframework.http.ResponseCookie
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * De lopende login (`state`, PKCE-verifier, `nonce`, return-pad) staat in een kortlevende pre-auth-sessie in de
 * [EenmaligeOpslag]. De browser krijgt alleen een verwijzend flowcookie: `__Host-`, `HttpOnly`, `Secure`, `Path=/`,
 * `SameSite=Lax` (de callback is een cross-site navigatie) en een korte `Max-Age`; er staat niets geheims in de browser.
 *
 * Het flowcookie is gebonden aan de host-tenant (een cookie van tenant A op tenant B levert niets op), eenmalig bruikbaar
 * (`GETDEL`: een replay faalt) en verloopt na de callback, ook bij een fout. `Lax` is alleen verantwoord omdat er geen
 * mutaties via `GET` zijn en CSRF-tokens gelden.
 */
class PreAutorisatieRepository(
    private val opslag: EenmaligeOpslag,
    private val levensduur: Duration,
) : ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private val mapper: JsonMapper = jacksonMapperBuilder().build()
    private val random = SecureRandom()

    /** Alleen tekst: wat Spring in het request zet is hier tekst gemaakt en komt zo terug. */
    private data class Opgeslagen(
        val oin: String,
        val authorizationUri: String,
        val clientId: String,
        val redirectUri: String?,
        val scopes: Set<String>,
        val state: String,
        val additionalParameters: Map<String, String>,
        val attributes: Map<String, String>,
        val authorizationRequestUri: String,
    )

    override fun saveAuthorizationRequest(
        authorizationRequest: OAuth2AuthorizationRequest,
        exchange: ServerWebExchange,
    ): Mono<Void> =
        TenantContext.currentMono().flatMap { oin ->
            val id = nieuwId()
            val opgeslagen =
                Opgeslagen(
                    oin = oin.value,
                    authorizationUri = authorizationRequest.authorizationUri,
                    clientId = authorizationRequest.clientId,
                    redirectUri = authorizationRequest.redirectUri,
                    scopes = authorizationRequest.scopes,
                    state = checkNotNull(authorizationRequest.state),
                    additionalParameters = authorizationRequest.additionalParameters.mapValues { it.value.toString() },
                    attributes = authorizationRequest.attributes.mapValues { it.value.toString() },
                    authorizationRequestUri = authorizationRequest.authorizationRequestUri,
                )
            opslag
                .zet(sleutel(id), mapper.writeValueAsString(opgeslagen), levensduur)
                .doOnSuccess { exchange.response.addCookie(cookie(id, levensduur)) }
        }

    override fun loadAuthorizationRequest(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> {
        val id = flowId(exchange) ?: return Mono.empty()
        return opslag.lees(sleutel(id)).flatMap { naarRequest(it, exchange) }
    }

    override fun removeAuthorizationRequest(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> {
        val id = flowId(exchange) ?: return Mono.empty()
        // het flowcookie gaat weg, ook bij een fout of een vreemde tenant
        exchange.response.addCookie(cookie("", Duration.ZERO))
        return opslag.neem(sleutel(id)).flatMap { naarRequest(it, exchange) }
    }

    /** Bij een geweigerde callback: pre-auth-sessie weg en flowcookie verlopen. */
    fun verwerp(exchange: ServerWebExchange): Mono<Void> {
        val id = flowId(exchange) ?: return Mono.empty()
        exchange.response.addCookie(cookie("", Duration.ZERO))
        return opslag.verwijder(sleutel(id))
    }

    private fun naarRequest(
        json: String,
        exchange: ServerWebExchange,
    ): Mono<OAuth2AuthorizationRequest> =
        TenantContext.currentMono().flatMap { oin ->
            val s: Opgeslagen = mapper.readValue(json)
            if (s.oin != oin.value) {
                Mono.empty()
            } else {
                s.attributes[BffNamen.RETURN_TO]?.let { exchange.attributes[BffNamen.EXCHANGE_RETURN_TO] = it }
                Mono.just(
                    OAuth2AuthorizationRequest
                        .authorizationCode()
                        .authorizationUri(s.authorizationUri)
                        .clientId(s.clientId)
                        .redirectUri(s.redirectUri)
                        .scopes(s.scopes)
                        .state(s.state)
                        .additionalParameters(s.additionalParameters)
                        .attributes { it.putAll(s.attributes) }
                        .authorizationRequestUri(s.authorizationRequestUri)
                        .build(),
                )
            }
        }

    private fun flowId(exchange: ServerWebExchange): String? = exchange.request.cookies.getFirst(BffNamen.FLOW_COOKIE)?.value?.takeIf { it.matches(ID_FORMAAT) }

    private fun sleutel(id: String) = "nlp:preauth:$id"

    private fun nieuwId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun cookie(
        waarde: String,
        maxAge: Duration,
    ): ResponseCookie =
        ResponseCookie
            .from(BffNamen.FLOW_COOKIE, waarde)
            .httpOnly(true)
            .secure(true)
            .path("/")
            .sameSite("Lax")
            .maxAge(maxAge)
            .build()

    private companion object {
        val ID_FORMAAT = Regex("[A-Za-z0-9_-]{43}")
    }
}
