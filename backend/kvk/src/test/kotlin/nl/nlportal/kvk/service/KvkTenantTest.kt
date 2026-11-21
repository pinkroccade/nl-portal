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
package nl.nlportal.kvk.service

import kotlinx.coroutines.runBlocking
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.kvk.TestHelper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Seam 1 (epic #499, T10): het KvK-nummer en het vestigingsnummer gaan alleen naar de koppeling van de eigen tenant. */
internal class KvkTenantTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD
    private lateinit var serverBosrand: MockWebServer
    private lateinit var serverWijdeVeld: MockWebServer
    private val bedrijf = JwtBuilder().vertegenwoordigdeKvk("90012768").buildAuthentication()
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
    fun `het KvK-nummer van een bedrijf van Bosrand gaat alleen naar de KvK-koppeling van Bosrand`() {
        serverBosrand.dispatcher = kvk()
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.getMaatschappelijkeActiviteit(bedrijf) }

        assertThat(resultaat?.naam).isEqualTo("Test bedrijf")
        assertThat(serverBosrand.requestCount).isEqualTo(1)
        assertThat(serverBosrand.takeRequest().path).isEqualTo("/basisprofielen/90012768")
        assertThat(serverWijdeVeld.requestCount).isZero()
    }

    @Test
    fun `het KvK-nummer van een bedrijf van Wijde Veld gaat alleen naar de KvK-koppeling van Wijde Veld`() {
        serverWijdeVeld.dispatcher = kvk()
        val service = service()

        TestTenants.metTenant(wijdeVeld) { service.getMaatschappelijkeActiviteit(bedrijf) }

        assertThat(serverWijdeVeld.takeRequest().path).isEqualTo("/basisprofielen/90012768")
        assertThat(serverBosrand.requestCount).isZero()
    }

    @Test
    fun `het vestigingsnummer gaat naar dezelfde tenant, met de eigen sleutel en Accept en zonder token`() {
        serverBosrand.dispatcher = kvk()
        serverWijdeVeld.dispatcher = kvk()
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.getKvkData("90012768", "990000262129") }

        assertThat(resultaat?.embedded?.vestiging?.vestigingsnummer).isEqualTo("990000262129")
        val verzoeken = (1..serverBosrand.requestCount).map { serverBosrand.takeRequest() }
        assertThat(verzoeken.map { it.path }).containsExactlyInAnyOrder("/basisprofielen/90012768", "/vestigingsprofielen/990000262129")
        verzoeken.forEach {
            assertThat(it.getHeader("X-API-KEY")).isEqualTo("sleutel-van-bosrand")
            assertThat(it.getHeader("Accept")).isEqualTo("application/json")
            assertThat(it.getHeader("Authorization")).isNull()
        }
        assertThat(serverWijdeVeld.requestCount).isZero()
    }

    @Test
    fun `een burger geeft geen aanroep naar de bron`() {
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.getMaatschappelijkeActiviteit(burger) }

        assertThat(resultaat).isNull()
        assertThat(serverBosrand.requestCount).isZero()
    }

    @Test
    fun `zonder tenant in de context faalt het ophalen gesloten`() {
        val service = service()

        assertThrows<TenantContextMissingException> { runBlocking { service.getKvkData("90012768") } }
        assertThrows<TenantContextMissingException> { runBlocking { service.getVestigingsData("990000262129") } }

        assertThat(serverBosrand.requestCount).isZero()
        assertThat(serverWijdeVeld.requestCount).isZero()
    }

    @Test
    fun `een tenant zonder KvK-koppeling krijgt geen stil null, voor elke operatie`() {
        val service = KvkService(TestTenants.register(TestTenants.tenant(bosrand)))

        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(bosrand) { service.getMaatschappelijkeActiviteit(bedrijf) } }
        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(bosrand) { service.getKvkData("90012768", "990000262129") } }
        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(bosrand) { service.getVestigingsData("990000262129") } }
    }

    @Test
    fun `een bronfout geeft null, ook als de bron het KvK-nummer in zijn foutbody noemt`() {
        serverBosrand.enqueue(
            MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/problem+json")
                .setBody("""{"type":"about:blank","title":"Bedrijf 90012768 onbekend","status":500,"detail":"KvK 90012768 niet gevonden"}"""),
        )
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.getKvkData("90012768") }

        assertThat(resultaat).isNull()
        assertThat(serverBosrand.requestCount).isEqualTo(1)
    }

    @Test
    fun `een bronfout bij de vestiging laat de hoofdvestiging staan`() {
        serverBosrand.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path?.startsWith("/vestigingsprofielen/") == true) {
                        MockResponse().setResponseCode(500)
                    } else {
                        TestHelper.mockResponseFromFile("/data/get-maatschappelijke-activiteiten.json")
                    }
            }
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.getKvkData("90012768", "990000262129") }

        assertThat(resultaat?.embedded?.vestiging?.vestigingsnummer).isEqualTo("000038509504")
    }

    @Test
    fun `een nummer met padtekens kan het pad niet verlaten en er gaat niets naar de bron`() {
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.getKvkData("../admin?x=1") }

        assertThat(resultaat).isNull()
        assertThat(serverBosrand.requestCount).isZero()
    }

    private fun service(): KvkService {
        val tenants =
            listOf(bosrand to serverBosrand, wijdeVeld to serverWijdeVeld).map { (oin, server) ->
                TestTenants.tenant(oin, koppelingen = mapOf("kvk" to koppeling(server, oin)))
            }
        val geheimen =
            TestTenants.geheimen(
                TestTenants.geheim("kvk-sleutel", bosrand) to "sleutel-van-bosrand",
                TestTenants.geheim("kvk-sleutel", wijdeVeld) to "sleutel-van-wijde-veld",
            )
        return KvkService(TestTenants.register(*tenants.toTypedArray(), geheimen = geheimen))
    }

    private fun koppeling(
        server: MockWebServer,
        oin: Oin,
    ) = TestTenants.koppeling(
        server.url("/").toString().trimEnd('/'),
        auth = TenantKoppelingAuth(TenantKoppelingAuthType.API_KEY, headerNaam = "X-API-KEY", waarde = TestTenants.geheim("kvk-sleutel", oin)),
        oin = oin,
    )

    private fun kvk() =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.startsWith("/vestigingsprofielen/") == true) {
                    TestHelper.mockResponseFromFile("/data/get-vestiging.json")
                } else {
                    TestHelper.mockResponseFromFile("/data/get-maatschappelijke-activiteiten.json")
                }
        }
}