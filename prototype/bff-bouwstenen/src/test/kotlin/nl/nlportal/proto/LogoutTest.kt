package nl.nlportal.proto

import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.EntityExchangeResult

/** Uitloggen vanuit de browser en back-channel logout met een OIDC-sessieregister in Redis. */
class LogoutTest : BffTestBase() {

    private val event = mapOf("http://schemas.openid.net/event/backchannel-logout" to emptyMap<String, Any>())

    private fun logoutToken(
        t: TenantFixture,
        sid: String? = null,
        sub: String = "999993653",
        claims: Map<String, Any> = mapOf("events" to event),
        audience: String = t.clientId,
        issuerId: String = t.id,
    ): String {
        val all = claims.toMutableMap()
        sid?.let { all["sid"] = it }
        return server.issueToken(
            issuerId,
            t.clientId,
            DefaultOAuth2TokenCallback(issuerId = issuerId, subject = sub, typeHeader = "logout+jwt", audience = listOf(audience), claims = all),
        ).serialize()
    }

    /** Keycloak roept de logout-URI van de tenant aan: server-naar-server, zonder cookies, op de host van de tenant. */
    private fun backChannel(t: TenantFixture, token: String, host: String = t.host, path: String = t.id): EntityExchangeResult<ByteArray> =
        client.post().uri("/logout/connect/back-channel/$path").header("Host", "$host:$port")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .bodyValue("logout_token=$token").exchange().expectBody().returnResult()

    private fun alive(b: Browser) = get(b, "/api/session").status.value() == 200

    @Test
    fun `uitloggen kan alleen via POST en beeindigt de sessie met RP-initiated logout`() {
        val b = login(bosrand).browser
        // GET beeindigt niets
        get(b, "/logout")
        assertThat(alive(b)).isTrue()

        val r = post(b, "/logout")
        assertThat(r.status.value()).isEqualTo(302)
        val location = r.responseHeaders.location.toString()
        assertThat(location).startsWith(server.endSessionEndpointUrl("bosrand").toString())
            .contains("id_token_hint=", "post_logout_redirect_uri=")
        val cookie = r.responseHeaders["Set-Cookie"].orEmpty().single { it.startsWith("$SESSION_COOKIE=") }
        assertThat(cookie.lowercase()).contains("max-age=0", "secure", "samesite=strict", "path=/").doesNotContain("domain")
        assertThat(alive(b)).isFalse()
        assertThat(redis.keys("spring:session:sessions:*").collectList().block()).isEmpty()
        assertThat(redis.keys("nlp:oidc:*").collectList().block()).isEmpty()
    }

    @Test
    fun `back-channel logout beeindigt alleen de sessie met die sid van die tenant`() {
        val target = login(bosrand, sid = "sid-doel")
        val otherSid = login(bosrand, sid = "sid-ander")
        val otherTenant = login(wijdeveld, sid = "sid-doel") // zelfde sid, andere realm

        val r = backChannel(bosrand, logoutToken(bosrand, sid = "sid-doel"))
        assertThat(r.status.value()).isEqualTo(200)

        assertThat(alive(target.browser)).isFalse()
        assertThat(alive(otherSid.browser)).isTrue()
        assertThat(alive(otherTenant.browser)).isTrue()
    }

    @Test
    fun `back-channel logout zonder sid beeindigt alle sessies van die gebruiker bij die tenant`() {
        val one = login(bosrand, subject = "111", sid = "s1")
        val two = login(bosrand, subject = "111", sid = "s2")
        val other = login(bosrand, subject = "222", sid = "s3")
        val r = backChannel(bosrand, logoutToken(bosrand, sub = "111"))
        assertThat(r.status.value()).isEqualTo(200)
        assertThat(alive(one.browser)).isFalse()
        assertThat(alive(two.browser)).isFalse()
        assertThat(alive(other.browser)).isTrue()
    }

    @Test
    fun `een ongeldig logout-token wordt geweigerd en laat sessies staan`() {
        val s = login(bosrand, sid = "sid-x")
        val bad = mapOf(
            "token van een andere issuer" to logoutToken(wijdeveld, sid = "sid-x"),
            "verkeerde audience" to logoutToken(bosrand, sid = "sid-x", audience = "iemand-anders"),
            "zonder events" to logoutToken(bosrand, sid = "sid-x", claims = emptyMap()),
            "met nonce" to logoutToken(bosrand, sid = "sid-x", claims = mapOf("events" to event, "nonce" to "n")),
            "geen jwt" to "geen-jwt",
            "ongetekend" to logoutToken(bosrand, sid = "sid-x").substringBeforeLast('.') + ".",
        )
        bad.forEach { (naam, token) ->
            val r = backChannel(bosrand, token)
            assertThat(r.status.value()).describedAs(naam).isEqualTo(400)
            assertThat(alive(s.browser)).describedAs(naam).isTrue()
        }
    }

    @Test
    fun `een replay van hetzelfde logout-token wordt geweigerd`() {
        val token = logoutToken(bosrand, sid = "sid-r")
        assertThat(backChannel(bosrand, token).status.value()).isEqualTo(200)
        val s = login(bosrand, sid = "sid-r")
        assertThat(backChannel(bosrand, token).status.value()).isEqualTo(400)
        assertThat(alive(s.browser)).isTrue()
    }

    @Test
    fun `de logout-uri loopt via de host-tenant`() {
        val s = login(bosrand, sid = "sid-h")
        val token = logoutToken(bosrand, sid = "sid-h")
        // op de host van een andere tenant, of op een onbekende host, bestaat de bosrand-uri niet
        assertThat(backChannel(bosrand, token, host = wijdeveld.host).status.value()).isEqualTo(404)
        assertThat(backChannel(bosrand, token, host = "onbekend.test").status.value()).isEqualTo(404)
        assertThat(alive(s.browser)).isTrue()
    }
}
