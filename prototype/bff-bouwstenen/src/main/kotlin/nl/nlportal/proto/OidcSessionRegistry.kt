package nl.nlportal.proto

import com.nimbusds.jwt.SignedJWT
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken
import org.springframework.security.oauth2.client.oidc.server.session.ReactiveOidcSessionRegistry
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.util.Date

/**
 * Register van OIDC-sessies in Redis: welke portaalsessie hoort bij welke `sid` (en `sub`) van welke issuer.
 * De sleutel bevat de issuer, dus een `sid` van de ene realm kan nooit een sessie van een andere realm raken.
 * Elke replica deelt dit register; een back-channel logout op een willekeurige replica beeindigt dus de sessie.
 */
class RedisOidcSessionRegistry(
    private val redis: ReactiveStringRedisTemplate,
    private val ttl: Duration,
) : ReactiveOidcSessionRegistry {
    private val mapper: JsonMapper = jacksonMapperBuilder().build()

    data class Stored(val sessionId: String, val authorities: Map<String, String>, val idToken: String)

    override fun saveSessionInformation(info: OidcSessionInformation): Mono<Void> {
        val idToken = info.principal.idToken
        val iss = idToken.issuer.toString()
        val stored = Stored(info.sessionId, info.authorities, idToken.tokenValue)
        val ops = redis.opsForValue()
        return ops.set(sessionKey(info.sessionId), mapper.writeValueAsString(stored), ttl)
            .then(index(sidKey(iss, idToken.getClaimAsString("sid")), info.sessionId))
            .then(index(subKey(iss, idToken.subject), info.sessionId))
    }

    override fun removeSessionInformation(sessionId: String): Mono<OidcSessionInformation> =
        redis.opsForValue().getAndDelete(sessionKey(sessionId)).flatMap { json ->
            val s: Stored = mapper.readValue(json)
            val info = toInformation(s)
            val idToken = info.principal.idToken
            val iss = idToken.issuer.toString()
            redis.opsForSet().remove(sidKey(iss, idToken.getClaimAsString("sid")), sessionId)
                .then(redis.opsForSet().remove(subKey(iss, idToken.subject), sessionId))
                .thenReturn(info)
        }

    /** Met `sid` precies die sessies; zonder `sid` alle sessies van de `sub` bij deze issuer. */
    override fun removeSessionInformation(logoutToken: OidcLogoutToken): Flux<OidcSessionInformation> {
        val iss = logoutToken.issuer.toString()
        val sid = logoutToken.sessionId
        val key = if (sid != null) sidKey(iss, sid) else subKey(iss, logoutToken.subject)
        return redis.opsForSet().members(key).flatMap { removeSessionInformation(it) }
    }

    private fun index(key: String, sessionId: String): Mono<Void> =
        redis.opsForSet().add(key, sessionId).then(redis.expire(key, ttl)).then()

    private fun toInformation(s: Stored): OidcSessionInformation {
        val claims = SignedJWT.parse(s.idToken).jwtClaimsSet.claims.mapValues { (_, v) ->
            if (v is Date) v.toInstant() else v
        }
        val idToken = OidcIdToken.withTokenValue(s.idToken).claims { it.putAll(claims) }.build()
        return OidcSessionInformation(s.sessionId, s.authorities, DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken))
    }

    private fun sessionKey(sessionId: String) = "nlp:oidc:session:$sessionId"

    private fun sidKey(iss: String, sid: String?) = "nlp:oidc:sid:$iss|$sid"

    private fun subKey(iss: String, sub: String?) = "nlp:oidc:sub:$iss|$sub"
}

