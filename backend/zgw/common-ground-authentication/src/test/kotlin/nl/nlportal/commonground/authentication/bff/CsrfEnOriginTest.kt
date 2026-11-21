/*
 * Copyright 2026 PinkRoccade Local Government B.V.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.nlportal.commonground.authentication.bff

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** CSRF-bescherming (token in cookie en header), de Origin-regel en de twee benoemde uitzonderingen. */
class CsrfEnOriginTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand
    private val wijdeveld = BffTestOmgeving.wijdeveld
    private lateinit var browser: Browser

    @BeforeEach
    fun ingelogd() {
        browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))
        assertThat(get(browser, BffNamen.SESSIEPAD).status.value()).isEqualTo(200)
    }

    @Test
    fun `een muterend request met Origin en CSRF-token wordt toegelaten`() {
        assertThat(post(browser, "/api/test/mutatie").status.value()).isEqualTo(200)
    }

    @Test
    fun `het CSRF-cookie is een Host-cookie dat bij het eerste request al wordt gezet`() {
        val vers = Browser(bosrand)

        val resultaat = get(vers, BffNamen.SESSIEPAD)

        val cookie = resultaat.responseHeaders["Set-Cookie"]!!.single { it.startsWith("__Host-XSRF-TOKEN=") }
        assertThat(cookie.lowercase()).contains("secure", "path=/", "samesite=strict").doesNotContain("domain", "httponly")
    }

    @Test
    fun `zonder CSRF-token wordt een mutatie geweigerd, ook met de juiste Origin`() {
        val resultaat = verstuur(browser, "POST", "/api/test/mutatie", mapOf("Origin" to origin(browser)), "{}", "application/json")

        assertThat(resultaat.status.value()).isEqualTo(403)
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.type }).contains(GebeurtenisType.CSRF_GEWEIGERD)
    }

    @Test
    fun `een CSRF-token dat niet bij het cookie hoort wordt geweigerd`() {
        val resultaat = post(browser, "/api/test/mutatie", headers = mapOf("X-XSRF-TOKEN" to "token-van-een-andere-sessie"))

        assertThat(resultaat.status.value()).isEqualTo(403)
    }

    @Test
    fun `een vreemde Origin wordt geweigerd, ook met een geldig token`() {
        listOf("https://evil.example", "http://${wijdeveld.host}:$poort", "null", origin(browser) + ".evil.example", origin(browser).replace("http://", "https://")).forEach {
            val resultaat = post(browser, "/api/test/mutatie", headers = mapOf("Origin" to it))
            assertThat(resultaat.status.value()).describedAs(it).isEqualTo(403)
        }
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.type }).contains(GebeurtenisType.ORIGIN_GEWEIGERD)
    }

    @Test
    fun `zonder Origin telt alleen Sec-Fetch-Site same-origin`() {
        val token = mapOf("X-XSRF-TOKEN" to browser.jar.getValue(BffNamen.CSRF_COOKIE))

        assertThat(verstuur(browser, "POST", "/api/test/mutatie", token + ("Sec-Fetch-Site" to "same-origin"), "{}", "application/json").status.value()).isEqualTo(200)
        assertThat(verstuur(browser, "POST", "/api/test/mutatie", token + ("Sec-Fetch-Site" to "cross-site"), "{}", "application/json").status.value()).isEqualTo(403)
        assertThat(verstuur(browser, "POST", "/api/test/mutatie", token + ("Sec-Fetch-Site" to "same-site"), "{}", "application/json").status.value()).isEqualTo(403)
        assertThat(verstuur(browser, "POST", "/api/test/mutatie", token, "{}", "application/json").status.value()).isEqualTo(403)
    }

    @Test
    fun `een formulierpost van een andere site met text-plain wordt geweigerd`() {
        val resultaat = verstuur(browser, "POST", "/api/test/mutatie", mapOf("Origin" to "https://evil.example"), "a=b", "text/plain")

        assertThat(resultaat.status.value()).isEqualTo(403)
    }

    @Test
    fun `een mutatie via GET bestaat niet`() {
        assertThat(get(browser, "/api/test/mutatie").status.value()).isEqualTo(405)
    }

    @Test
    fun `GraphQL accepteert alleen application-json`() {
        assertThat(post(browser, "/graphql", body = "query=x", inhoudstype = "application/x-www-form-urlencoded").status.value()).isEqualTo(415)
        assertThat(post(browser, "/graphql", body = "query=x", inhoudstype = "text/plain").status.value()).isEqualTo(415)
        assertThat(post(browser, "/graphql", body = "{}", inhoudstype = "application/json").status.value()).isEqualTo(200)
    }

    @Test
    fun `de postsale-webhook is de uitzondering op CSRF en de Origin-regel`() {
        val resultaat = verstuur(Browser(bosrand), "POST", "/api/public/payment/direct/postsale", body = "{}", inhoudstype = "application/json")

        assertThat(resultaat.status.value()).isEqualTo(200)
    }

    @Test
    fun `de uitzondering geldt alleen voor het exacte pad van de webhook`() {
        val resultaat = verstuur(Browser(bosrand), "POST", "/api/public/payment/direct/postsale/extra", body = "{}", inhoudstype = "application/json")

        assertThat(resultaat.status.value()).isEqualTo(403)
    }
}