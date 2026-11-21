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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLEncoder

class InloggenTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand
    private val wijdeveld = BffTestOmgeving.wijdeveld

    @Test
    fun `een onbekende host krijgt een kale 404, ook op de login en de callback`() {
        val onbekend = Browser(bosrand, hostOverride = "onbekend.test")
        listOf("/", "${BffNamen.INLOGPAD}${bosrand.oin}", "${BffNamen.CALLBACKPAD}${bosrand.oin}?code=x&state=y", "/logout").forEach {
            val resultaat = get(onbekend, it)
            assertThat(resultaat.status.value()).describedAs(it).isEqualTo(404)
            assertThat(resultaat.responseBody).describedAs(it).isNullOrEmpty()
        }
    }

    @Test
    fun `een vreemde registratie-id geeft 404 op de login en de callback`() {
        val browser = Browser(bosrand)

        assertThat(get(browser, "${BffNamen.INLOGPAD}${wijdeveld.oin}").status.value()).isEqualTo(404)
        assertThat(get(browser, "${BffNamen.INLOGPAD}onbekend").status.value()).isEqualTo(404)
        assertThat(get(browser, "${BffNamen.CALLBACKPAD}${wijdeveld.oin}?code=x&state=y&iss=${bosrand.issuer}").status.value()).isEqualTo(404)
    }

    /**
     * Alle cookies van de hele levensloop (eerste request, login-start, callback, gewoon verkeer, uitloggen) in een test: alleen deze
     * drie bestaan, elk als `__Host-`-cookie (dus Secure, Path=/, geen Domain) met het SameSite en de HttpOnly die erbij horen.
     * De test draait over http: de cookies zijn dan toch Secure, want `__Host-` laat niet anders toe; browsers accepteren dat op
     * `localhost` (veilige context), op een ander http-adres wordt het cookie geweigerd en werkt inloggen dus niet.
     */
    @Test
    fun `alle cookies hebben de vaste attributen, ook over http in ontwikkeling`() {
        val browser = Browser(bosrand)
        val ruw = mutableListOf<String>()

        fun vang(resultaat: org.springframework.test.web.reactive.server.EntityExchangeResult<ByteArray>) {
            ruw += resultaat.responseHeaders["Set-Cookie"].orEmpty()
        }
        vang(get(browser, BffNamen.SESSIEPAD))
        val start = get(browser, "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=digid")
        vang(start)
        val terug = idpAutoriseer(start.responseHeaders.location!!, bosrand.tokens.spec.subject)
        vang(callback(browser, terug, bosrand.issuer))
        vang(get(browser, BffNamen.SESSIEPAD))
        vang(post(browser, BffNamen.UITLOGPAD, body = null, inhoudstype = null))

        val perNaam = ruw.groupBy { it.substringBefore("=") }
        assertThat(perNaam.keys).containsExactlyInAnyOrder(BffNamen.SESSIE_COOKIE, BffNamen.FLOW_COOKIE, BffNamen.CSRF_COOKIE)
        val verwacht =
            mapOf(
                BffNamen.SESSIE_COOKIE to Triple(true, "Strict", false),
                BffNamen.FLOW_COOKIE to Triple(true, "Lax", false),
                BffNamen.CSRF_COOKIE to Triple(false, "Strict", false),
            )
        verwacht.forEach { (naam, eisen) ->
            perNaam.getValue(naam).forEach { cookie ->
                val attributen = cookie.split(";").drop(1).map { it.trim().lowercase() }
                assertThat(attributen).describedAs(cookie).contains("secure", "path=/", "samesite=${eisen.second.lowercase()}")
                assertThat(attributen.any { it.startsWith("domain") }).describedAs("Domain in $cookie").isEqualTo(eisen.third)
                assertThat(attributen.contains("httponly")).describedAs("HttpOnly in $cookie").isEqualTo(eisen.first)
            }
        }
        assertThat(ruw.filter { it.startsWith(BffNamen.SESSIE_COOKIE) }).describedAs("sessiecookie: gezet en gewist").anySatisfy { assertThat(it).doesNotContain("Max-Age=0") }
    }

    @Test
    fun `login per tenant met PKCE, state, nonce en private_key_jwt van de eigen sleutel`() {
        BffTestOmgeving.tenants.forEach { tenant ->
            val browser = Browser(tenant)
            tenant.tokens.spec = TokenSpec(subject = "gebruiker-${tenant.naam}")

            val start = get(browser, "${BffNamen.INLOGPAD}${tenant.oin}?inlogmiddel=digid")
            assertThat(start.status.value()).isEqualTo(302)
            val autorisatie = start.responseHeaders.location!!
            assertThat(autorisatie.toString()).startsWith(BffTestOmgeving.provider.authorizationEndpointUrl(tenant.naam).toString())
            assertThat(autorisatie.query).contains("code_challenge_method=S256", "state=", "nonce=", "client_id=${tenant.clientId}", "prompt=login", "kc_idp_hint=digid")
            assertThat(autorisatie.query).doesNotContain("client_secret")
            val flow = start.responseHeaders["Set-Cookie"]!!.single { it.startsWith("__Host-nlp-flow=") }
            assertThat(flow.lowercase()).contains("httponly", "secure", "path=/", "samesite=lax").doesNotContain("domain")
            assertThat(flow).containsPattern("Max-Age=\\d+")

            val terug = idpAutoriseer(autorisatie, tenant.tokens.spec.subject)
            val callback = callback(browser, terug, tenant.issuer)
            assertThat(callback.status.value()).isEqualTo(200)
            val cookies = callback.responseHeaders["Set-Cookie"]!!
            val sessie = cookies.single { it.startsWith("__Host-Http-NLP=") }
            assertThat(sessie.lowercase()).contains("httponly", "secure", "path=/", "samesite=strict").doesNotContain("domain")
            assertThat(cookies.single { it.startsWith("__Host-nlp-flow=") }).contains("Max-Age=0")
            assertThat(browser.jar.keys).doesNotContain(BffNamen.FLOW_COOKIE)

            controleerAssertion(tokenVerzoek(tenant), tenant)

            val wie = get(browser, "/api/test/wie")
            assertThat(wie.status.value()).isEqualTo(200)
            assertThat(String(wie.responseBody!!)).contains("gebruiker-${tenant.naam}", "\"bsn\":\"999993653\"")
        }
    }

    @Test
    fun `het sessie-endpoint zegt wie er is ingelogd zonder BSN of token`() {
        val inlog = log(bosrand, TokenSpec(subject = "geheime-sub"))
        slaagt(inlog)

        val sessie = get(inlog.browser, BffNamen.SESSIEPAD)

        val body = String(sessie.responseBody!!)
        assertThat(sessie.status.value()).isEqualTo(200)
        assertThat(body).contains("\"ingelogd\":true", "\"vertegenwoordigde\":\"burger\"", "\"namensEenAnder\":false", "\"inlogmiddel\":\"digid\"", "verlooptOp", "inactiviteitSeconden\":900")
        assertThat(body).doesNotContain("999993653", "geheime-sub", "eyJ")
        assertThat(sessie.responseHeaders.cacheControl).contains("no-store")
    }

    @Test
    fun `zonder sessie is het sessie-endpoint anoniem met het login-pad en de ingeschakelde middelen, en een beveiligd pad 401`() {
        val browser = Browser(bosrand)

        assertThat(String(get(browser, BffNamen.SESSIEPAD).responseBody!!))
            .isEqualTo("{\"ingelogd\":false,\"loginPad\":\"/oauth2/authorization/${bosrand.oin}\",\"inlogmiddelen\":[\"digid\",\"test\"]}")
        assertThat(get(browser, "/api/test/wie").status.value()).isEqualTo(401)
    }

    @Test
    fun `het testinlogmiddel is een gewoon inlogmiddel met niveau Basis`() {
        val spec = TokenSpec(inlogmiddel = "test", acr = "Basis", actingSubjectId = "999993847")
        val inlog = log(bosrand, spec, startQuery = "inlogmiddel=test")

        slaagt(inlog)
        assertThat(
            inlog.start.responseHeaders.location!!
                .query,
        ).doesNotContain("kc_idp_hint")
        assertThat(String(get(inlog.browser, "/api/test/wie").responseBody!!)).contains("\"niveau\":\"basis\"", "999993847")
    }

    @Test
    fun `het sessie-ID wijzigt bij inloggen en een door de aanvaller gekozen ID wordt niet overgenomen`() {
        val browser = Browser(bosrand)
        browser.jar[BffNamen.SESSIE_COOKIE] = "aanvaller-gekozen-id"

        val inlog = log(bosrand, browser = browser)

        slaagt(inlog)
        val nieuw = browser.jar.getValue(BffNamen.SESSIE_COOKIE)
        assertThat(nieuw).isNotEqualTo("aanvaller-gekozen-id").isNotEmpty()
        val aanvaller = Browser(bosrand)
        aanvaller.jar[BffNamen.SESSIE_COOKIE] = "aanvaller-gekozen-id"
        assertThat(get(aanvaller, "/api/test/wie").status.value()).isEqualTo(401)
    }

    @Test
    fun `opnieuw inloggen met een bestaande sessie geeft een nieuw sessie-ID en maakt het oude waardeloos`() {
        val eerste = log(bosrand)
        slaagt(eerste)
        val oud = eerste.browser.jar.getValue(BffNamen.SESSIE_COOKIE)

        val tweede = log(bosrand, browser = eerste.browser)

        slaagt(tweede)
        val nieuw = eerste.browser.jar.getValue(BffNamen.SESSIE_COOKIE)
        assertThat(nieuw).isNotEqualTo(oud)
        val oudeBrowser = Browser(bosrand)
        oudeBrowser.jar[BffNamen.SESSIE_COOKIE] = oud
        assertThat(get(oudeBrowser, "/api/test/wie").status.value()).isEqualTo(401)
    }

    @Test
    fun `sessie-ID's zijn onderling verschillend`() {
        val ids = (1..5).map { log(bosrand).browser.jar.getValue(BffNamen.SESSIE_COOKIE) }

        assertThat(ids).doesNotHaveDuplicates()
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://evil.example/pad", "//evil.example", "/\\evil.example", "javascript:alert(1)", "evil", "/pad;x", "/pad<script>"])
    fun `een return-pad dat geen veilig relatief pad is valt terug op de startpagina`(returnTo: String) {
        val inlog = log(bosrand, startQuery = "inlogmiddel=digid&returnTo=" + URLEncoder.encode(returnTo, Charsets.UTF_8))

        slaagt(inlog)
        assertThat(String(inlog.callback.responseBody!!)).contains("url=/\"").doesNotContain("evil")
    }

    @Test
    fun `een veilig return-pad gaat geescaped in de tussenpagina met een CSP en een gewone link`() {
        val inlog = log(bosrand, startQuery = "inlogmiddel=digid&returnTo=" + URLEncoder.encode("/zaken?a=1&b=2", Charsets.UTF_8))

        val body = String(inlog.callback.responseBody!!)
        assertThat(body).contains("url=/zaken?a=1&amp;b=2\"").contains("<a href=\"/zaken?a=1&amp;b=2\">")
        assertThat(inlog.callback.responseHeaders.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'")
        assertThat(inlog.callback.responseHeaders.cacheControl).contains("no-store")
    }

    @Test
    fun `een bearer-token wordt nergens geaccepteerd`() {
        val inlog = log(bosrand)
        slaagt(inlog)
        val jwt = tokenVan(inlog)

        val zonderCookie = get(Browser(bosrand), "/api/test/wie", mapOf("Authorization" to "Bearer $jwt"))

        assertThat(zonderCookie.status.value()).isEqualTo(401)
    }

    private fun tokenVan(inlog: Inlog): String = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0."

    @Test
    fun `inloggen met een uitgeschakeld inlogmiddel wordt bij de start geweigerd`() {
        val browser = Browser(wijdeveld)

        val start = get(browser, "${BffNamen.INLOGPAD}${wijdeveld.oin}?inlogmiddel=test")

        assertThat(start.status.value()).isEqualTo(400)
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.type }).containsExactly(GebeurtenisType.LOGIN_START_GEWEIGERD)
    }

    @Test
    fun `zonder inlogmiddel kiest een tenant met maar een middel dat middel, met meer middelen is het een 400`() {
        assertThat(get(Browser(wijdeveld), "${BffNamen.INLOGPAD}${wijdeveld.oin}").status.value()).isEqualTo(302)
        assertThat(get(Browser(bosrand), "${BffNamen.INLOGPAD}${bosrand.oin}").status.value()).isEqualTo(400)
        assertThat(get(Browser(bosrand), "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=onbekend").status.value()).isEqualTo(400)
    }
}