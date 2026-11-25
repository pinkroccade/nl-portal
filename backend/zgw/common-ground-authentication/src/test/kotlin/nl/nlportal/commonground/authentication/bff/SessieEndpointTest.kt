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
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import tools.jackson.databind.json.JsonMapper

/** Het sessie-endpoint (`GET /api/public/sessie`): tijden per tenant, `verlooptOp`, en nooit een token of BSN, op elk pad. */
class SessieEndpointTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand
    private val wijdeveld = BffTestOmgeving.wijdeveld
    private val klok = BffTestOmgeving.klok
    private val mapper = JsonMapper.builder().build()

    private fun sessieBody(browser: Browser): Pair<Map<String, Any?>, String> {
        val resultaat = get(browser, BffNamen.SESSIEPAD)
        assertThat(resultaat.status.value()).isEqualTo(200)
        assertThat(resultaat.responseHeaders.cacheControl).contains("no-store")
        val tekst = String(resultaat.responseBody!!)
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(tekst, Map::class.java) as Map<String, Any?> to tekst
    }

    private fun geenGeheimen(tekst: String) {
        assertThat(tekst).doesNotContain("eyJ", "999993653", "s00000000", "gebruiker-1", "sid-1", "access_token", "id_token", "refresh")
    }

    @Test
    fun `verlooptOp is het aanmaakmoment plus de absolute duur van de tenant, en schuift niet op door activiteit`() {
        val start = klok.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))

        val (eerste, _) = sessieBody(browser)
        klok.advance(Duration.ofMinutes(10))
        get(browser, "/api/test/wie")
        val (later, _) = sessieBody(browser)

        assertThat(Instant.parse(eerste["verlooptOp"] as String)).isEqualTo(start.plus(Duration.ofHours(8)))
        assertThat(later["verlooptOp"]).describedAs("de absolute duur schuift niet op").isEqualTo(eerste["verlooptOp"])
        assertThat(eerste["inactiviteitSeconden"]).isEqualTo(900)
    }

    @Test
    fun `een tenant met strengere tijden krijgt die tijden in het sessie-endpoint`() {
        val start = klok.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val browser = Browser(wijdeveld)
        slaagt(log(wijdeveld, browser = browser, startQuery = "inlogmiddel=digid"))

        val (status, _) = sessieBody(browser)

        assertThat(Instant.parse(status["verlooptOp"] as String)).isEqualTo(start.plus(Duration.ofHours(2)))
        assertThat(status["inactiviteitSeconden"]).isEqualTo(600)
    }

    @Test
    fun `een ingelogde sessie toont alleen de afgesproken velden, zonder token of BSN`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, TokenSpec(subject = "gebruiker-1", sid = "sid-1"), browser))

        val (status, tekst) = sessieBody(browser)

        assertThat(status.keys).containsExactlyInAnyOrder("ingelogd", "vertegenwoordigde", "namensEenAnder", "inlogmiddel", "verlooptOp", "inactiviteitSeconden")
        assertThat(status["ingelogd"]).isEqualTo(true)
        assertThat(status["vertegenwoordigde"]).isEqualTo("burger")
        assertThat(status["namensEenAnder"]).isEqualTo(false)
        assertThat(status["inlogmiddel"]).isEqualTo("digid")
        geenGeheimen(tekst)
    }

    @Test
    fun `een anonieme bezoeker krijgt het loginpad en de inlogmiddelen van de eigen tenant, zonder geheimen`() {
        val (bos, bosTekst) = sessieBody(Browser(bosrand))
        val (wijd, wijdTekst) = sessieBody(Browser(wijdeveld))

        assertThat(bos).containsEntry("ingelogd", false).containsEntry("loginPad", "${BffNamen.INLOGPAD}${bosrand.oin}")
        assertThat(bos["inlogmiddelen"]).isEqualTo(listOf("digid", "gebruikersnaam"))
        assertThat(bos.keys).containsExactlyInAnyOrder("ingelogd", "loginPad", "inlogmiddelen")
        assertThat(wijd).containsEntry("loginPad", "${BffNamen.INLOGPAD}${wijdeveld.oin}")
        assertThat(wijd["inlogmiddelen"]).isEqualTo(listOf("digid"))
        geenGeheimen(bosTekst + wijdTekst)
    }

    @Test
    fun `na verlopen van de sessie meldt het endpoint niet ingelogd, zonder gegevens van de oude sessie`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))
        klok.advance(Duration.ofMinutes(15))

        val (status, tekst) = sessieBody(browser)

        assertThat(status["ingelogd"]).isEqualTo(false)
        assertThat(status).doesNotContainKeys("verlooptOp", "inlogmiddel", "vertegenwoordigde")
        geenGeheimen(tekst)
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.reden }).contains(Reden.VERLOPEN_INACTIVITEIT)
    }

    @Test
    fun `een gekaapte sessie van een andere tenant geeft op het sessie-endpoint niet ingelogd, zonder gegevens`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))
        val opAnder = Browser(wijdeveld)
        opAnder.jar.putAll(browser.jar)

        val (status, tekst) = sessieBody(opAnder)

        assertThat(status["ingelogd"]).isEqualTo(false)
        assertThat(status.keys).containsExactlyInAnyOrder("ingelogd", "loginPad", "inlogmiddelen")
        geenGeheimen(tekst)
    }

    @Test
    fun `het sessie-endpoint geeft ook zonder cookie en met een onbekend cookie nooit gegevens`() {
        val zonder = Browser(bosrand)
        val onbekend = Browser(bosrand).also { it.jar[BffNamen.SESSIE_COOKIE] = "bestaat-niet" }

        listOf(zonder, onbekend).forEach {
            val (status, tekst) = sessieBody(it)
            assertThat(status["ingelogd"]).isEqualTo(false)
            geenGeheimen(tekst)
        }
    }

    @Test
    fun `de logout-redirect gebruikt de IdP, client en terugkeer-URL van de eigen tenant, ook als er meer tenants zijn`() {
        val browser = Browser(wijdeveld)
        slaagt(log(wijdeveld, browser = browser, startQuery = "inlogmiddel=digid"))
        get(browser, BffNamen.SESSIEPAD)

        val resultaat = post(browser, BffNamen.UITLOGPAD, body = null, inhoudstype = null)

        val doel = resultaat.responseHeaders.location!!
        assertThat(doel.toString()).startsWith(BffTestOmgeving.provider.endSessionEndpointUrl("wijdeveld").toString())
        val parameters = doel.rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
        assertThat(parameters["client_id"]).isEqualTo(wijdeveld.clientId)
        assertThat(parameters["post_logout_redirect_uri"]).isEqualTo("${origin(browser)}/")
        assertThat(parameters["id_token_hint"]).startsWith("eyJ")
        assertThat(parameters.keys).containsExactlyInAnyOrder("id_token_hint", "client_id", "post_logout_redirect_uri")
        assertThat(beveiligingsregels.joinToString()).doesNotContain("eyJ", "999993653")
    }
}