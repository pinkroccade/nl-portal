package nl.nlportal.proto

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
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

const val FLOW_COOKIE = "__Host-nlp-flow"
const val RETURN_TO_ATTRIBUTE = "nlp.returnTo"

/**
 * De lopende login (state, PKCE-verifier, nonce) staat in een kortlevende pre-auth-sessie in Redis. De browser krijgt
 * alleen een verwijzend flowcookie: `__Host-`, HttpOnly, Secure, Lax (de callback is een cross-site navigatie).
 * Eenmalig (GETDEL), gebonden aan de host-tenant, en na de callback weg.
 */
class RedisPreAuthRepository(
    private val redis: ReactiveStringRedisTemplate,
    private val maxAge: Duration,
) : ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private val mapper: JsonMapper = jacksonMapperBuilder().build()
    private val random = SecureRandom()

    data class Stored(
        val tenant: String,
        val authorizationUri: String,
        val clientId: String,
        val redirectUri: String?,
        val scopes: Set<String>,
        val state: String,
        val additionalParameters: Map<String, String>,
        val attributes: Map<String, String>,
        val authorizationRequestUri: String,
    )

    override fun saveAuthorizationRequest(request: OAuth2AuthorizationRequest, exchange: ServerWebExchange): Mono<Void> {
        val id = newId()
        val stored = Stored(
            tenant = exchange.tenant.id,
            authorizationUri = request.authorizationUri,
            clientId = request.clientId,
            redirectUri = request.redirectUri,
            scopes = request.scopes,
            state = request.state!!,
            additionalParameters = request.additionalParameters.mapValues { it.value.toString() },
            attributes = request.attributes.mapValues { it.value.toString() },
            authorizationRequestUri = request.authorizationRequestUri,
        )
        return redis.opsForValue().set(key(id), mapper.writeValueAsString(stored), maxAge)
            .doOnNext { exchange.response.addCookie(cookie(id, maxAge)) }
            .then()
    }

    override fun loadAuthorizationRequest(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> {
        val id = flowId(exchange) ?: return Mono.empty()
        return redis.opsForValue().get(key(id)).flatMap { toRequest(it, exchange) }
    }

    override fun removeAuthorizationRequest(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> {
        val id = flowId(exchange) ?: return Mono.empty()
        // het flowcookie gaat weg, ook bij een fout of een vreemde tenant
        exchange.response.addCookie(cookie("", Duration.ZERO))
        return redis.opsForValue().getAndDelete(key(id)).flatMap { toRequest(it, exchange) }
    }

    /** Bij een geweigerde callback: pre-auth-sessie weg en cookie verlopen. */
    fun discard(exchange: ServerWebExchange): Mono<Void> {
        val id = flowId(exchange) ?: return Mono.empty()
        exchange.response.addCookie(cookie("", Duration.ZERO))
        return redis.delete(key(id)).then()
    }

    private fun toRequest(json: String, exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> {
        val s: Stored = mapper.readValue(json)
        if (s.tenant != exchange.tenant.id) return Mono.empty()
        s.attributes["return_to"]?.let { exchange.attributes[RETURN_TO_ATTRIBUTE] = it }
        return Mono.just(
            OAuth2AuthorizationRequest.authorizationCode()
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

    private fun flowId(exchange: ServerWebExchange): String? =
        exchange.request.cookies.getFirst(FLOW_COOKIE)?.value?.takeIf { it.matches(ID_FORMAT) }


    private fun key(id: String) = "nlp:preauth:$id"

    private fun newId(): String =
        ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun cookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(FLOW_COOKIE, value).httpOnly(true).secure(true).path("/").sameSite("Lax")
            .maxAge(maxAge).build()

    private companion object {
        val ID_FORMAT = Regex("[A-Za-z0-9_-]{43}")
    }
}
