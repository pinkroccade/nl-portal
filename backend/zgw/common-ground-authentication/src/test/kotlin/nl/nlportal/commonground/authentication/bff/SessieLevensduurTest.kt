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
import java.time.Duration

/** Inactiviteit, absolute duur en de binding van de sessie aan de tenant, met een bestuurbare klok. */
class SessieLevensduurTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand
    private val wijdeveld = BffTestOmgeving.wijdeveld
    private val klok = BffTestOmgeving.klok

    private fun werkt(browser: Browser) = get(browser, "/api/test/wie").status.value() == 200

    @Test
    fun `na precies de inactiviteitstijd verloopt de sessie, daarvoor niet`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))

        klok.advance(Duration.ofMinutes(14).plusSeconds(59))
        assertThat(werkt(browser)).describedAs("14m59 na de laatste activiteit").isTrue()
        klok.advance(Duration.ofMinutes(14).plusSeconds(59))
        assertThat(werkt(browser)).describedAs("de activiteit schuift het einde op").isTrue()
        klok.advance(Duration.ofMinutes(15))
        assertThat(werkt(browser)).describedAs("15m zonder activiteit").isFalse()

        assertThat(BffTestOmgeving.gebeurtenissen.map { it.reden }).contains(Reden.VERLOPEN_INACTIVITEIT)
        assertThat(browser.jar.keys).describedAs("het sessiecookie verloopt").doesNotContain(BffNamen.SESSIE_COOKIE)
    }

    @Test
    fun `het sessie-endpoint telt niet als activiteit`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))

        klok.advance(Duration.ofMinutes(10))
        get(browser, BffNamen.SESSIEPAD)
        klok.advance(Duration.ofMinutes(6))

        assertThat(werkt(browser)).isFalse()
    }

    @Test
    fun `de sessie verloopt na de absolute duur ook bij voortdurende activiteit`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))

        repeat(34) {
            klok.advance(Duration.ofMinutes(14))
            assertThat(werkt(browser)).describedAs("na ${(it + 1) * 14} minuten").isTrue()
        }
        klok.advance(Duration.ofMinutes(14))

        assertThat(werkt(browser)).describedAs("na 7h56 + 14m").isFalse()
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.reden }).contains(Reden.VERLOPEN_ABSOLUUT)
    }

    @Test
    fun `een tenant kan strenger zijn dan de grenzen van de ADR`() {
        val browser = Browser(wijdeveld)
        slaagt(log(wijdeveld, browser = browser, startQuery = "inlogmiddel=digid"))

        klok.advance(Duration.ofMinutes(9).plusSeconds(59))
        assertThat(werkt(browser)).isTrue()
        klok.advance(Duration.ofMinutes(10))

        assertThat(werkt(browser)).isFalse()
    }

    @Test
    fun `een sessie van De Bosrand wordt op Het Wijde Veld geweigerd met een securityevent en is daarna ongeldig`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))
        val sessie = browser.jar.getValue(BffNamen.SESSIE_COOKIE)
        val opAnderDomein = Browser(wijdeveld)
        opAnderDomein.jar[BffNamen.SESSIE_COOKIE] = sessie

        assertThat(werkt(opAnderDomein)).isFalse()

        val event = BffTestOmgeving.gebeurtenissen.single { it.type == GebeurtenisType.SESSIE_TENANT_MISMATCH }
        assertThat(event.oin).isEqualTo(wijdeveld.oin.value)
        assertThat(event.reden).isEqualTo(Reden.TENANT)
        val terug = Browser(bosrand)
        terug.jar[BffNamen.SESSIE_COOKIE] = sessie
        assertThat(werkt(terug)).describedAs("de gekaapte sessie is bij de mismatch ongeldig gemaakt").isFalse()
    }

    @Test
    fun `een sessie met onbekende inhoud wordt niet vertrouwd`() {
        val browser = Browser(bosrand)
        browser.jar[BffNamen.SESSIE_COOKIE] = "bestaat-niet"

        assertThat(werkt(browser)).isFalse()
    }
}