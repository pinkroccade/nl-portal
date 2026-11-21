/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.haalcentraalbrp.service

import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.haalcentraalbrp.tenant.BrpConfiguratie
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Seam 1 (epic #499, T9): het BSN gaat alleen naar de koppeling van de eigen tenant. */
internal class HaalCentraalBrpTenantTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD
    private lateinit var serverBosrand: MockWebServer
    private lateinit var serverWijdeVeld: MockWebServer
    private val burger = JwtBuilder().vertegenwoordigdeBsn("999993653").buildAuthentication()

    @BeforeEach
    fun setUp() {
        serverBosrand = MockWebServer().also { it.start() }
        serverWijdeVeld = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        serverBosrand.shutdown()
        serverWijdeVeld.shutdown()
    }

    @Test
    fun `het BSN van een inwoner van Bosrand gaat alleen naar de BRP van Bosrand`() {
        serverBosrand.enqueue(personen())
        val service = service()

        TestTenants.metTenant(bosrand) { service.getPersoon(burger) }

        assertThat(serverBosrand.requestCount).isEqualTo(1)
        assertThat(serverBosrand.takeRequest().body.readUtf8()).contains("999993653")
        assertThat(serverWijdeVeld.requestCount).isZero()
    }

    @Test
    fun `het BSN van een inwoner van Wijde Veld gaat alleen naar de BRP van Wijde Veld`() {
        serverWijdeVeld.enqueue(personen())
        val service = service()

        TestTenants.metTenant(wijdeVeld) { service.getPersoon(burger) }

        assertThat(serverWijdeVeld.requestCount).isEqualTo(1)
        assertThat(serverBosrand.requestCount).isZero()
    }

    @Test
    fun `de velden en aanvullende headers van de eigen tenant gaan mee, met de sleutel van de koppeling en zonder token`() {
        serverBosrand.enqueue(personen())
        serverWijdeVeld.enqueue(personen())
        val service =
            service(
                listOf(
                    bosrand to BrpConfiguratie(velden = listOf("naam"), aanvullendeHeaders = mapOf("x-doelbinding" to "bosrand")),
                    wijdeVeld to BrpConfiguratie(velden = listOf("leeftijd"), aanvullendeHeaders = mapOf("x-doelbinding" to "wijde-veld")),
                ),
            )

        TestTenants.metTenant(bosrand) { service.getPersoon(burger) }
        TestTenants.metTenant(wijdeVeld) { service.getPersoon(burger) }

        val bosrandVerzoek = serverBosrand.takeRequest()
        assertThat(bosrandVerzoek.path).isEqualTo("/personen")
        assertThat(bosrandVerzoek.body.readUtf8()).contains("\"fields\":[\"naam\"]")
        assertThat(bosrandVerzoek.getHeader("x-doelbinding")).isEqualTo("bosrand")
        assertThat(bosrandVerzoek.getHeader("X-API-KEY")).isNotBlank()
        assertThat(bosrandVerzoek.getHeader("Authorization")).isNull()
        val wijdeVeldVerzoek = serverWijdeVeld.takeRequest()
        assertThat(wijdeVeldVerzoek.body.readUtf8()).contains("\"fields\":[\"leeftijd\"]")
        assertThat(wijdeVeldVerzoek.getHeader("x-doelbinding")).isEqualTo("wijde-veld")
    }

    @Test
    fun `de woonplaatsen voor bewoning zijn die van de eigen tenant`() {
        serverBosrand.enqueue(bewoningen())
        val service =
            service(
                listOf(
                    bosrand to BrpConfiguratie(toegestaneWoonplaatsenBewoning = listOf("Bosrand")),
                    wijdeVeld to BrpConfiguratie(toegestaneWoonplaatsenBewoning = listOf("Wijde Veld")),
                ),
            )

        val bosrandAantal = TestTenants.metTenant(bosrand) { service.getBewonersAantal(burger, "1", "Bosrand") }
        val wijdeVeldAantal = TestTenants.metTenant(wijdeVeld) { service.getBewonersAantal(burger, "1", "Bosrand") }

        assertThat(bosrandAantal).isEqualTo(1)
        assertThat(wijdeVeldAantal).isNull()
        assertThat(serverBosrand.requestCount).isEqualTo(1)
        assertThat(serverWijdeVeld.requestCount).isZero()
    }

    @Test
    fun `bewoningen gaan naar de bewoningskoppeling van de eigen tenant`() {
        val bewoningServer = MockWebServer().also { it.start() }
        try {
            bewoningServer.enqueue(bewoningen())
            val service = service(listOf(bosrand to BrpConfiguratie()), bewoning = mapOf(bosrand to bewoningServer))

            val resultaat = TestTenants.metTenant(bosrand) { service.getBewoningen(burger, "1") }

            assertThat(resultaat?.bewoningen).hasSize(1)
            assertThat(bewoningServer.takeRequest().path).isEqualTo("/bewoningen")
            assertThat(serverBosrand.requestCount).isZero()
        } finally {
            bewoningServer.shutdown()
        }
    }

    @Test
    fun `zonder bewoningskoppeling geeft bewoningen null en gaat er niets naar de personenkoppeling`() {
        val service = service(listOf(bosrand to BrpConfiguratie()), metBewoning = false)

        val resultaat = TestTenants.metTenant(bosrand) { service.getBewoningen(burger, "1") }

        assertThat(resultaat).isNull()
        assertThat(serverBosrand.requestCount).isZero()
    }

    @Test
    fun `zonder tenant in de context faalt het ophalen gesloten`() {
        val service = service()

        assertThrows<TenantContextMissingException> { runBlocking { service.getPersoon(burger) } }

        assertThat(serverBosrand.requestCount).isZero()
        assertThat(serverWijdeVeld.requestCount).isZero()
    }

    @Test
    fun `een tenant zonder BRP-configuratie krijgt geen BRP en de bron wordt niet aangeroepen`() {
        val tenant = TestTenants.tenant(bosrand, koppelingen = mapOf("brp" to koppeling(serverBosrand, bosrand)))
        val service = HaalCentraalBrpService(TestTenants.register(tenant), TestTenants.directory(tenant))

        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(bosrand) { service.getPersoon(burger) } }

        assertThat(serverBosrand.requestCount).isZero()
    }

    @Test
    fun `een bron die faalt geeft een koppelingsfout zonder het BSN in het bericht`() {
        serverBosrand.enqueue(MockResponse().setResponseCode(500))
        val service = service()

        val fout = assertThrows<KoppelingException> { TestTenants.metTenant(bosrand) { service.getPersoon(burger) } }

        assertThat(fout.message).doesNotContain("999993653")
    }

    private fun koppeling(
        server: MockWebServer,
        oin: Oin,
    ) = TestTenants.koppeling(
        server.url("/").toString().trimEnd('/'),
        auth = TenantKoppelingAuth(TenantKoppelingAuthType.API_KEY, headerNaam = "X-API-KEY", waarde = TestTenants.geheim("brp-sleutel", oin)),
        oin = oin,
    )

    private fun service(
        configuraties: List<Pair<Oin, BrpConfiguratie>> = listOf(bosrand to BrpConfiguratie(), wijdeVeld to BrpConfiguratie()),
        bewoning: Map<Oin, MockWebServer> = emptyMap(),
        metBewoning: Boolean = true,
    ): HaalCentraalBrpService {
        val servers = mapOf(bosrand to serverBosrand, wijdeVeld to serverWijdeVeld)
        val tenants =
            configuraties.map { (oin, configuratie) ->
                val koppelingen =
                    buildMap {
                        put("brp", koppeling(servers.getValue(oin), oin))
                        if (metBewoning) put("brp-bewoning", koppeling(bewoning[oin] ?: servers.getValue(oin), oin))
                    }
                TestTenants.tenant(oin, koppelingen = koppelingen, modules = mapOf("brp" to configuratie))
            }
        return HaalCentraalBrpService(TestTenants.register(*tenants.toTypedArray()), TestTenants.directory(*tenants.toTypedArray()))
    }

    private fun bewoningen() =
        MockResponse()
            .setBody("""{"bewoningen": [{"adresseerbaarObjectIdentificatie": "1", "bewoners": [{"burgerservicenummer": "1"}]}]}""")
            .addHeader("Content-Type", "application/json")

    private fun personen() =
        MockResponse()
            .setBody("""{"type": "RaadpleegMetBurgerservicenummer", "personen": [{}]}""")
            .addHeader("Content-Type", "application/json")
}