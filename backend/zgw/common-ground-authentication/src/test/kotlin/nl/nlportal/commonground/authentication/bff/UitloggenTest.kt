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

/** Uitloggen alleen via `POST` met CSRF; de portaalsessie is weg en de Keycloak-sessie eindigt via RP-initiated logout. */
class UitloggenTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand

    private fun ingelogd(): Browser {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))
        get(browser, BffNamen.SESSIEPAD)
        return browser
    }

    @Test
    fun `een GET op de logout beeindigt niets`() {
        val browser = ingelogd()

        get(browser, BffNamen.UITLOGPAD)

        assertThat(get(browser, "/api/test/wie").status.value()).isEqualTo(200)
    }

    @Test
    fun `een POST zonder CSRF-token beeindigt niets`() {
        val browser = ingelogd()

        val resultaat = verstuur(browser, "POST", BffNamen.UITLOGPAD, mapOf("Origin" to origin(browser)))

        assertThat(resultaat.status.value()).isEqualTo(403)
        assertThat(get(browser, "/api/test/wie").status.value()).isEqualTo(200)
    }

    @Test
    fun `uitloggen beeindigt de sessie en stuurt naar het end_session_endpoint met id_token_hint`() {
        val browser = ingelogd()
        val oudeSessie = browser.jar.getValue(BffNamen.SESSIE_COOKIE)

        val resultaat = post(browser, BffNamen.UITLOGPAD, body = null, inhoudstype = null)

        assertThat(resultaat.status.value()).isEqualTo(302)
        val doel = resultaat.responseHeaders.location!!
        assertThat(doel.toString()).startsWith(BffTestOmgeving.provider.endSessionEndpointUrl("bosrand").toString())
        val parameters = doel.rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
        assertThat(parameters["id_token_hint"]).startsWith("eyJ")
        assertThat(parameters["client_id"]).isEqualTo(bosrand.clientId)
        assertThat(parameters["post_logout_redirect_uri"]).isEqualTo("${origin(browser)}/")
        assertThat(browser.jar.keys).doesNotContain(BffNamen.SESSIE_COOKIE)
        val oud = Browser(bosrand)
        oud.jar[BffNamen.SESSIE_COOKIE] = oudeSessie
        assertThat(get(oud, "/api/test/wie").status.value()).describedAs("de oude sessie is weg").isEqualTo(401)
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.type }).contains(GebeurtenisType.UITGELOGD)
    }

    @Test
    fun `uitloggen zonder sessie stuurt terug naar het portaal`() {
        val browser = Browser(bosrand)
        get(browser, BffNamen.SESSIEPAD)

        val resultaat = post(browser, BffNamen.UITLOGPAD, body = null, inhoudstype = null)

        assertThat(resultaat.status.value()).isEqualTo(302)
        assertThat(resultaat.responseHeaders.location.toString()).isEqualTo("/")
    }
}