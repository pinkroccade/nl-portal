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
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Beleid voor `iat`, `exp` en `jti` van een logout-token. Spring controleert `exp` alleen als die er is en kent geen
 * maximale ouderdom; wij eisen alle drie, begrenzen de ouderdom en de levensduur, en koppelen de levensduur van de
 * `jti`-sleutel aan `exp`, zodat een token niet na het verlopen van de sleutel nog geldig is.
 */
object LogoutTokenPolicy {
    val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    val MAX_AGE: Duration = Duration.ofMinutes(2)
    val MAX_LIFETIME: Duration = Duration.ofMinutes(10)

    /** De levensduur van de `jti`-sleutel, of `null` als het token niet voldoet. */
    fun jtiTtl(token: OidcLogoutToken, now: Instant): Duration? {
        token.getClaimAsString("jti") ?: return null
        if (token.sessionId.isNullOrBlank() && token.subject.isNullOrBlank()) return null // par. 2.4: sub of sid
        val iat = token.getClaimAsInstant("iat") ?: return null
        val exp = token.getClaimAsInstant("exp") ?: return null
        if (iat.isBefore(now.minus(MAX_AGE).minus(CLOCK_SKEW))) return null
        if (iat.isAfter(now.plus(CLOCK_SKEW))) return null
        if (Duration.between(iat, exp) > MAX_LIFETIME) return null
        if (!exp.plus(CLOCK_SKEW).isAfter(now)) return null
        return Duration.between(now, exp).plus(CLOCK_SKEW)
    }
}

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
    private val clock: Clock,
) : ServerLogoutHandler {
    override fun logout(exchange: WebFilterExchange, authentication: Authentication): Mono<Void> {
        val token = authentication.principal as OidcLogoutToken
        val ttl = LogoutTokenPolicy.jtiTtl(token, clock.instant()) ?: return reject(exchange)
        val key = "nlp:oidc:jti:${token.issuer}|${token.getClaimAsString("jti")}"
        return redis.opsForValue().setIfAbsent(key, "1", ttl).flatMap { first ->
            if (!first) {
                reject(exchange)
            } else {
                // faalt het beeindigen, dan geeft de jti-sleutel vrij: de OP mag het opnieuw proberen (par. 2.8)
                registry.removeSessionInformation(token).flatMap { sessions.deleteById(it.sessionId) }.then()
                    .onErrorResume { e -> redis.delete(key).then(Mono.error(e)) }
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
