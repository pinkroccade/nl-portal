package nl.nlportal.proto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken
import java.time.Duration
import java.time.Instant

/** Het beleid voor `iat`, `exp` en `jti` van een logout-token (OIDC Back-Channel Logout 1.0 par. 2.4 en 2.6, ASVS 10.5.5). */
class LogoutTokenPolicyTest {
    private val now = Instant.parse("2026-11-21T10:00:00Z")

    private fun token(
        jti: String? = "jti-1",
        iat: Instant? = now,
        exp: Instant? = now.plusSeconds(120),
    ): OidcLogoutToken = OidcLogoutToken.withTokenValue("t").issuer("https://idp").audience(listOf("c")).subject("s")
        .events(mapOf("http://schemas.openid.net/event/backchannel-logout" to emptyMap<String, Any>()))
        .claims { c ->
            jti?.let { c["jti"] = it }
            iat?.let { c["iat"] = it }
            exp?.let { c["exp"] = it }
        }.build()

    @Test
    fun `een token met jti, iat en exp krijgt een jti-levensduur tot exp plus klokmarge`() {
        assertThat(LogoutTokenPolicy.jtiTtl(token(), now)).isEqualTo(Duration.ofSeconds(120).plus(LogoutTokenPolicy.CLOCK_SKEW))
    }

    @Test
    fun `een token zonder exp of iat wordt geweigerd`() {
        assertThat(LogoutTokenPolicy.jtiTtl(token(exp = null), now)).isNull()
        assertThat(LogoutTokenPolicy.jtiTtl(token(iat = null), now)).isNull()
    }

    @Test
    fun `een te oud, te lang geldig of verlopen token wordt geweigerd`() {
        val tooOld = now.minus(LogoutTokenPolicy.MAX_AGE).minus(LogoutTokenPolicy.CLOCK_SKEW).minusSeconds(1)
        assertThat(LogoutTokenPolicy.jtiTtl(token(iat = tooOld, exp = now.plusSeconds(60)), now)).isNull()
        assertThat(LogoutTokenPolicy.jtiTtl(token(exp = now.plus(LogoutTokenPolicy.MAX_LIFETIME).plusSeconds(1)), now)).isNull()
        assertThat(LogoutTokenPolicy.jtiTtl(token(iat = now.minusSeconds(100), exp = now.minusSeconds(61)), now)).isNull()
    }

    @Test
    fun `een iat in de toekomst voorbij de klokmarge wordt geweigerd`() {
        assertThat(LogoutTokenPolicy.jtiTtl(token(iat = now.plusSeconds(120), exp = now.plusSeconds(200)), now)).isNull()
    }
}
