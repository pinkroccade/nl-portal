package nl.nlportal.proto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

/** De flowcookie en pre-auth-sessie, en de issuercontrole van de authorization response (RFC 9207). */
class FlowSecurityTest : BffTestBase() {

    private class Started(val browser: Browser, val callbackUri: URI, val flowCookie: String)

    private fun start(t: TenantFixture): Started {
        enqueueTokens(t)
        val b = Browser(t.host)
        val s = get(b, "/oauth2/authorization/${t.id}")
        val back = idpAuthorize(s.responseHeaders.location!!)
        return Started(b, back, b.jar.getValue("__Host-nlp-flow"))
    }

    private fun sessionCookieSet(r: org.springframework.test.web.reactive.server.EntityExchangeResult<*>) =
        r.responseHeaders["Set-Cookie"].orEmpty().any { it.startsWith(SESSION_COOKIE) }

    @Test
    fun `het flowcookie van tenant A wordt op tenant B geweigerd`() {
        val s = start(bosrand)
        val other = Browser(wijdeveld.host).also { it.jar["__Host-nlp-flow"] = s.flowCookie }
        val r = callback(other, URI.create(s.callbackUri.toString().replace("/bosrand", "/wijdeveld")), wijdeveld.issuer)
        assertThat(r.status.value()).isEqualTo(401)
        assertThat(sessionCookieSet(r)).isFalse()
        // het flowcookie is eenmalig: ook op de eigen tenant is het nu verbruikt
        val own = callback(s.browser, s.callbackUri, bosrand.issuer)
        assertThat(own.status.value()).isEqualTo(401)
    }

    @Test
    fun `een replay van de callback faalt en het flowcookie is na de callback weg`() {
        val s = start(bosrand)
        val first = callback(s.browser, s.callbackUri, bosrand.issuer)
        assertThat(first.status.value()).isEqualTo(200)
        assertThat(s.browser.jar).doesNotContainKey("__Host-nlp-flow")

        val replay = Browser(bosrand.host).also { it.jar["__Host-nlp-flow"] = s.flowCookie }
        val r = callback(replay, s.callbackUri, bosrand.issuer)
        assertThat(r.status.value()).isEqualTo(401)
        assertThat(sessionCookieSet(r)).isFalse()
    }

    @Test
    fun `een callback zonder flowcookie of met een andere state faalt`() {
        val s = start(bosrand)
        val noCookie = callback(Browser(bosrand.host), s.callbackUri, bosrand.issuer)
        assertThat(noCookie.status.value()).isEqualTo(401)

        val tampered = URI.create(s.callbackUri.toString().replace("state=", "state=x"))
        val r = callback(s.browser, tampered, bosrand.issuer)
        assertThat(r.status.value()).isEqualTo(401)
        assertThat(sessionCookieSet(r)).isFalse()
    }

    @Test
    fun `een issuer die niet overeenkomt of ontbreekt wordt geweigerd en ruimt de flow op`() {
        listOf<String?>(wijdeveld.issuer, null, bosrand.issuer + "/").forEach { iss ->
            val s = start(bosrand)
            val r = callback(s.browser, s.callbackUri, iss)
            assertThat(r.status.value()).isEqualTo(400)
            assertThat(sessionCookieSet(r)).isFalse()
            assertThat(r.responseHeaders["Set-Cookie"]!!.single()).contains("Max-Age=0")
            // de flow is opgeruimd: de juiste issuer helpt daarna niet meer
            val retry = Browser(bosrand.host).also { it.jar["__Host-nlp-flow"] = s.flowCookie }
            assertThat(callback(retry, s.callbackUri, bosrand.issuer).status.value()).isEqualTo(401)
        }
    }

    @Test
    fun `een vreemde registratie-id op de host van een tenant geeft 404`() {
        val b = Browser(bosrand.host)
        assertThat(get(b, "/oauth2/authorization/wijdeveld").status.value()).isEqualTo(404)
        assertThat(get(b, "/login/oauth2/code/wijdeveld?code=x&state=y").status.value()).isEqualTo(404)
        assertThat(get(b, "/oauth2/authorization/bestaat-niet").status.value()).isEqualTo(404)
    }

    @Test
    fun `een return-pad is alleen relatief toegestaan, een open redirect wordt genegeerd`() {
        mapOf(
            "/aanvragen/42" to "/aanvragen/42",
            "https://evil.example/" to "/",
            "//evil.example/" to "/",
            "/\\evil.example" to "/",
            "javascript:alert(1)" to "/",
        ).forEach { (returnTo, expected) ->
            enqueueTokens(bosrand)
            val b = Browser(bosrand.host)
            val enc = java.net.URLEncoder.encode(returnTo, Charsets.UTF_8)
            val s = get(b, "/oauth2/authorization/bosrand?returnTo=$enc")
            val back = idpAuthorize(s.responseHeaders.location!!)
            val cb = callback(b, back, bosrand.issuer)
            assertThat(String(cb.responseBody!!)).contains("content=\"0;url=$expected\"")
        }
    }
}
