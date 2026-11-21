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
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Acceptatiecriterium uit de securitytoets van T9: een sessie van tenant A op de host van tenant B geeft 401 en veroorzaakt
 * nul uitgaande koppelingsaanroepen. De bron achter [BffTestOmgeving.bron] telt elk uitgaand verzoek van de app.
 */
class TenantBindingTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand
    private val wijdeveld = BffTestOmgeving.wijdeveld

    private fun uitgaand() = BffTestOmgeving.bron.requestCount

    @Test
    fun `de telling van uitgaande aanroepen werkt, want een sessie op de eigen host bereikt de bron`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, browser = browser))
        val voor = uitgaand()

        val resultaat = get(browser, "/api/test/koppeling")

        assertThat(resultaat.status.value()).isEqualTo(200)
        assertThat(uitgaand() - voor).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0} -> {1}: {2} {3}")
    @MethodSource("richtingen")
    fun `een sessie van tenant A op de host van tenant B geeft 401 en nul uitgaande aanroepen`(
        van: String,
        naar: String,
        methode: String,
        pad: String,
    ) {
        val a = BffTestOmgeving.tenants.single { it.naam == van }
        val b = BffTestOmgeving.tenants.single { it.naam == naar }
        val browserA = Browser(a)
        slaagt(log(a, browser = browserA))
        val opB = Browser(b)
        opB.jar.putAll(browserA.jar)
        val voor = uitgaand()

        val resultaat = if (methode == "GET") get(opB, pad) else post(opB, pad)

        assertThat(resultaat.status.value()).isEqualTo(401)
        assertThat(uitgaand()).describedAs("uitgaande koppelingsaanroepen").isEqualTo(voor)
        val event = BffTestOmgeving.gebeurtenissen.single { it.type == GebeurtenisType.SESSIE_TENANT_MISMATCH }
        assertThat(event.oin).isEqualTo(b.oin.value)
        assertThat(event.reden).isEqualTo(Reden.TENANT)
        assertThat(beveiligingsregels.single { "sessie_tenant_mismatch" in it }).contains("oin=${b.oin}", "reden=tenant")
    }

    @Test
    fun `na een mismatch is de sessie ook op de eigen host ongeldig en blijft de bron onaangeroepen`() {
        val browserA = Browser(bosrand)
        slaagt(log(bosrand, browser = browserA))
        val opB = Browser(wijdeveld)
        opB.jar.putAll(browserA.jar)
        val voor = uitgaand()
        get(opB, "/api/test/koppeling")

        val terug = get(browserA, "/api/test/koppeling")

        assertThat(terug.status.value()).isEqualTo(401)
        assertThat(uitgaand()).isEqualTo(voor)
    }

    companion object {
        @JvmStatic
        fun richtingen(): Stream<Arguments> {
            // /graphql staat bewust open op HTTP-niveau (permitAll in core); de autorisatie gebeurt per resolver op de identiteit, die hier ontbreekt
            val paden = listOf("GET" to "/api/test/koppeling", "GET" to "/api/test/wie", "POST" to "/api/test/mutatie")
            return Stream.of("bosrand" to "wijdeveld", "wijdeveld" to "bosrand").flatMap { (van, naar) ->
                paden.stream().map { (methode, pad) -> Arguments.of(van, naar, methode, pad) }
            }
        }
    }
}