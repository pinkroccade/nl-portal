package nl.nlportal.proto

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

/**
 * RFC 9207: de `iss`-parameter van de authorization response moet exact gelijk zijn aan de issuer van de
 * registratie van de host-tenant. Ontbreekt hij, dan weigeren we (fail-closed: de broker ondersteunt hem).
 * Bij een weigering gaat de pre-auth-sessie weg en verloopt het flowcookie.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class IssuerCheckFilter(private val preAuth: RedisPreAuthRepository) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!exchange.request.path.value().startsWith("/login/oauth2/code/")) return chain.filter(exchange)
        val iss = exchange.request.queryParams.getFirst("iss")
        val expected = exchange.tenant.properties.issuer
        if (iss == expected && exchange.request.queryParams["iss"]!!.size == 1) return chain.filter(exchange)
        exchange.response.statusCode = HttpStatus.BAD_REQUEST
        return preAuth.discard(exchange).then(exchange.response.setComplete())
    }
}

/**
 * Absolute timeout (injecteerbare klok) en tenantbinding van de sessie. Een verlopen of vreemde sessie wordt
 * ongeldig gemaakt; het request gaat daarna ongeauthenticeerd verder (401).
 */
@Component
@Order(-110)
class SessionGuardFilter(private val clock: Clock, private val properties: ProtoProperties) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (exchange.request.cookies.getFirst(SESSION_COOKIE) == null) return chain.filter(exchange)
        return exchange.session.flatMap { session ->
            if (!session.isStarted) return@flatMap chain.filter(exchange)
            val tenant = session.getAttribute<String>(SESSION_TENANT)
            val created = session.getAttribute<Instant>(SESSION_CREATED_AT)
            val expired = created == null || !clock.instant().isBefore(created.plus(properties.absoluteSessionTimeout))
            val foreign = tenant != exchange.tenantOrNull?.id
            if (expired || foreign) {
                session.attributes.clear()
                session.invalidate().then(chain.filter(exchange))
            } else {
                chain.filter(exchange)
            }
        }.switchIfEmpty(Mono.defer { chain.filter(exchange) })
    }
}

/** Een `returnTo`-pad komt alleen mee in de pre-auth-sessie als het een veilig relatief pad is (geen open redirect). */
class ReturnToAuthorizationRequestResolver(
    private val delegate: DefaultServerOAuth2AuthorizationRequestResolver,
) : ServerOAuth2AuthorizationRequestResolver {
    override fun resolve(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> =
        delegate.resolve(exchange).map { withReturnTo(it, exchange) }

    override fun resolve(exchange: ServerWebExchange, clientRegistrationId: String): Mono<OAuth2AuthorizationRequest> =
        delegate.resolve(exchange, clientRegistrationId).map { withReturnTo(it, exchange) }

    private fun withReturnTo(request: OAuth2AuthorizationRequest, exchange: ServerWebExchange): OAuth2AuthorizationRequest {
        val raw = exchange.request.queryParams.getFirst("returnTo")
        val decoded = raw?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        if (decoded == null || !SAFE_PATH.matches(decoded)) return request
        return OAuth2AuthorizationRequest.from(request).attributes { it["return_to"] = decoded }.build()
    }

    private companion object {
        val SAFE_PATH = Regex("^/(?!/)[A-Za-z0-9/_.~%=&?-]*$")
    }
}
