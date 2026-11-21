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

/**
 * `/graphql` is `permitAll` (de autorisatie gebeurt per resolver op de identiteit). Een sessie van tenant A op de host van
 * tenant B wordt daar daarom geen 401 maar anoniem: de resolver krijgt geen identiteit, het antwoord is een fout en de bron
 * wordt niet aangeroepen.
 */
class GraphQlSessieTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand
    private val wijdeveld = BffTestOmgeving.wijdeveld

    private fun uitgaand() = BffTestOmgeving.bron.requestCount

    private fun vraag(
        browser: Browser,
        query: String,
    ) = post(browser, "/graphql", body = """{"query":"$query"}""")

    @Test
    fun `een query met een sessie op de eigen host krijgt de identiteit en bereikt de bron`() {
        val browser = Browser(bosrand)
        slaagt(log(bosrand, TokenSpec(subject = "gebruiker-1"), browser))
        get(browser, BffNamen.SESSIEPAD)
        val voor = uitgaand()

        val resultaat = vraag(browser, "{ bffTestWie }")

        assertThat(resultaat.status.value()).isEqualTo(200)
        assertThat(String(resultaat.responseBody!!)).contains("gebruiker-1").doesNotContain("errors")
        assertThat(uitgaand() - voor).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["{ bffTestWie }", "mutation { bffTestMutatie }"])
    fun `een sessie van tenant A op de host van tenant B geeft in GraphQL geen identiteit, een fout en nul uitgaande aanroepen`(query: String) {
        val browserA = Browser(bosrand)
        slaagt(log(bosrand, TokenSpec(subject = "gebruiker-van-a"), browserA))
        get(browserA, BffNamen.SESSIEPAD)
        val opB = Browser(wijdeveld)
        opB.jar.putAll(browserA.jar)
        val voor = uitgaand()

        val resultaat = vraag(opB, query)

        val body = String(resultaat.responseBody!!)
        assertThat(body).describedAs(body).contains("errors", "No Authentication").doesNotContain("gebruiker-van-a", "999993653")
        assertThat(body).describedAs(body).containsPattern("\"data\":(null|[{]\"[A-Za-z]+\":null[}])")
        assertThat(uitgaand()).describedAs("uitgaande aanroepen").isEqualTo(voor)
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.type }).contains(GebeurtenisType.SESSIE_TENANT_MISMATCH)
    }

    @Test
    fun `een anonieme GraphQL-aanroep geeft een fout en nul uitgaande aanroepen`() {
        val voor = uitgaand()

        val anoniem = Browser(bosrand)
        get(anoniem, BffNamen.SESSIEPAD)

        val resultaat = vraag(anoniem, "{ bffTestWie }")

        assertThat(resultaat.status.value()).isEqualTo(200)
        assertThat(String(resultaat.responseBody!!)).contains("errors", "No Authentication")
        assertThat(uitgaand()).isEqualTo(voor)
    }
}