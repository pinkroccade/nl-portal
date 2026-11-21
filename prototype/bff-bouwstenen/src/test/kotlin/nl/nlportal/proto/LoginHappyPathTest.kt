package nl.nlportal.proto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LoginHappyPathTest : BffTestBase() {

    @Test
    fun `onbekende host geeft 404 zonder inhoud`() {
        val r = get(Browser("onbekend.test"), "/")
        assertThat(r.status.value()).isEqualTo(404)
        assertThat(r.responseBody).isNullOrEmpty()
    }

    @Test
    fun `login per tenant met private_key_jwt van de eigen sleutel en eerste paginalading na de callback`() {
        listOf(bosrand, wijdeveld).forEach { t ->
            val b = Browser(t.host)
            enqueueTokens(t, subject = "BSN-${t.id}")

            // 1. login-start: redirect naar de IdP van de tenant; flowcookie, nog geen sessiecookie
            val start = get(b, "/oauth2/authorization/${t.id}")
            assertThat(start.status.value()).isEqualTo(302)
            val authorize = start.responseHeaders.location!!
            assertThat(authorize.toString()).startsWith(server.authorizationEndpointUrl(t.id).toString())
            assertThat(authorize.query).contains("code_challenge_method=S256", "state=", "nonce=", "client_id=${t.clientId}")
            val flow = start.responseHeaders["Set-Cookie"]!!.single()
            assertThat(flow.lowercase()).startsWith("__host-nlp-flow=")
                .contains("httponly", "secure", "path=/", "samesite=lax").doesNotContain("domain")
            assertThat(flow).containsPattern("Max-Age=\\d+")
            assertThat(b.jar.keys).containsExactly(FLOW_COOKIE)

            // 2. callback: sessiecookie __Host-Http-, Strict, flowcookie weg
            val back = idpAuthorize(authorize)
            val cb = callback(b, back, t.issuer)
            assertThat(cb.status.value()).isEqualTo(200)
            val cookies = cb.responseHeaders["Set-Cookie"]!!
            val session = cookies.single { it.startsWith("__Host-Http-NLP=") }
            assertThat(session.lowercase()).contains("httponly", "secure", "path=/", "samesite=strict").doesNotContain("domain")
            assertThat(cookies.single { it.startsWith("__Host-nlp-flow=") }).contains("Max-Age=0")
            assertThat(b.jar.keys).containsExactly("__Host-Http-NLP")

            // 3. client-assertion van deze tenant, geen client_secret
            verifyAssertion(tokenRequest(t), t)

            // 4. eerste paginalading met het Strict-cookie
            val page = get(b, "/api/session")
            assertThat(page.status.value()).isEqualTo(200)
            assertThat(String(page.responseBody!!)).contains("BSN-${t.id}").contains("\"tenant\":\"${t.id}\"")
        }
    }

    @Test
    fun `de tussenpagina escapet het return-pad, decodeert een keer, en heeft een CSP en een gewone link`() {
        val b = Browser(bosrand.host)
        enqueueTokens(bosrand)
        val start = get(b, "/oauth2/authorization/bosrand?returnTo=" + java.net.URLEncoder.encode("/pad?a=1&b=2&c=x%20y", Charsets.UTF_8))
        val cb = callback(b, idpAuthorize(start.responseHeaders.location!!), bosrand.issuer)
        val body = String(cb.responseBody!!)
        assertThat(body).contains("url=/pad?a=1&amp;b=2&amp;c=x%20y\"").contains("<a href=\"/pad?a=1&amp;b=2&amp;c=x%20y\">")
        assertThat(cb.responseHeaders.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'")
    }
}
