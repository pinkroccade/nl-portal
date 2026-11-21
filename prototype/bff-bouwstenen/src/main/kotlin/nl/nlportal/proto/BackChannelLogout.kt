package nl.nlportal.proto

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken
import org.springframework.security.oauth2.client.oidc.server.session.ReactiveOidcSessionRegistry
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler
import org.springframework.session.ReactiveSessionRepository
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Back-channel logout. Spring valideert het logout-token (handtekening, `iss`, `aud`, `events`, geen `nonce`,
 * `sub` of `sid`) tegen de registratie van de host-tenant. Wij voegen toe:
 *  - replaybescherming op `jti` (Redis, `SETNX` met TTL), want Spring doet dat niet;
 *  - het beeindigen van de portaalsessie rechtstreeks in de sessiestore. De standaardhandler van Spring doet een
 *    HTTP-aanroep naar de eigen `/logout` met het sessiecookie; die werkt niet met een eigen cookienaam
 *    (`__Host-Http-`) en niet achter een host-tenant die intern niet resolvet.
 */
class BackChannelLogoutHandler(
    private val registry: ReactiveOidcSessionRegistry,
    private val sessions: ReactiveSessionRepository<*>,
    private val redis: ReactiveStringRedisTemplate,
    private val jtiTtl: Duration = Duration.ofMinutes(10),
) : ServerLogoutHandler {
    override fun logout(exchange: WebFilterExchange, authentication: Authentication): Mono<Void> {
        val token = authentication.principal as OidcLogoutToken
        val jti = token.getClaimAsString("jti") ?: return reject(exchange)
        return redis.opsForValue().setIfAbsent("nlp:oidc:jti:${token.issuer}|$jti", "1", jtiTtl).flatMap { first ->
            if (!first) {
                reject(exchange)
            } else {
                registry.removeSessionInformation(token).flatMap { sessions.deleteById(it.sessionId) }.then()
            }
        }
    }

    private fun reject(exchange: WebFilterExchange): Mono<Void> {
        // Spring zet daarna zelf nog een status; beforeCommit loopt als laatste
        exchange.exchange.response.beforeCommit {
            exchange.exchange.response.statusCode = HttpStatus.BAD_REQUEST
            Mono.empty()
        }
        return Mono.empty()
    }
}

/**
 * Spring Security 7.1.2 geeft een 500 in plaats van een 400 als een logout-token geen `events`-claim heeft
 * (`IllegalArgumentException` bij het opbouwen van het token, verpakt in een `AuthenticationServiceException` die de
 * filter niet afhandelt). Voor het back-channel-pad zetten we elke authenticatiefout om naar een 400 (RFC-conform:
 * een ongeldig logout-token is een `invalid_request`).
 */
@org.springframework.stereotype.Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class BackChannelErrorHandler : org.springframework.web.server.WebExceptionHandler {
    override fun handle(exchange: org.springframework.web.server.ServerWebExchange, ex: Throwable): Mono<Void> {
        val isBackChannel = exchange.request.path.value().startsWith("/logout/connect/back-channel/")
        val isAuthError = generateSequence(ex) { it.cause }.any { it is org.springframework.security.core.AuthenticationException }
        if (!isBackChannel || !isAuthError || exchange.response.isCommitted) return Mono.error(ex)
        exchange.response.statusCode = HttpStatus.BAD_REQUEST
        return exchange.response.setComplete()
    }
}
