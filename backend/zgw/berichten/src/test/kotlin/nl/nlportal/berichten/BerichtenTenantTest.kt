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
package nl.nlportal.berichten

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.test.runTest
import nl.nlportal.berichten.configuratie.BerichtenConfiguratie
import nl.nlportal.berichten.service.BerichtenService
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock

/** Seam 1: het BSN van een inwoner en het objecttype van Berichten gaan alleen naar de Objecten-koppeling en de configuratie van de eigen tenant. */
class BerichtenTenantTest {
    private lateinit var bosrand: MockWebServer
    private lateinit var wijdeVeld: MockWebServer
    private lateinit var service: BerichtenService

    private val inwoner = JwtBuilder().vertegenwoordigdeBsn(BSN).buildAuthentication()

    @BeforeEach
    fun start() {
        bosrand = MockWebServer().also { it.start() }
        wijdeVeld = MockWebServer().also { it.start() }
        val tenants =
            arrayOf(
                tenant(TestTenants.BOSRAND, bosrand, TYPE_BOSRAND),
                tenant(TestTenants.WIJDE_VELD, wijdeVeld, TYPE_WIJDE_VELD),
            )
        service = BerichtenService(ObjectenApiService(ObjectsApiClient(TestTenants.register(*tenants))), mock<DocumentenApiService>(), TestTenants.directory(*tenants))
    }

    @AfterEach
    fun stop() {
        bosrand.shutdown()
        wijdeVeld.shutdown()
    }

    @Test
    fun `de berichten van een inwoner worden alleen bij de eigen tenant opgevraagd met het eigen objecttype`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.WIJDE_VELD))) {
            wijdeVeld.enqueue(leeg())

            service.getBerichtenPage(inwoner, 1, 10, null)

            val verzoek = wijdeVeld.takeRequest()
            assertThat(verzoek.path).contains("/api/v2/objects").contains(BSN).contains("type=").contains("wijde-veld-bericht")
            assertThat(verzoek.path).doesNotContain("bosrand-bericht")
            assertThat(bosrand.requestCount).isZero()
        }

    @Test
    fun `het aantal ongeopende berichten gaat alleen naar de eigen tenant`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(leeg())

            service.getUnopenedBerichtenCount(inwoner)

            val verzoek = bosrand.takeRequest()
            assertThat(verzoek.path).contains(BSN).contains("bosrand-bericht")
            assertThat(wijdeVeld.requestCount).isZero()
        }

    @Test
    fun `een tenant zonder berichten-configuratie krijgt geen verzoek en geen stil resultaat`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.WIJDE_VELD))) {
            val zonder = tenant(TestTenants.WIJDE_VELD, wijdeVeld, type = null)
            val zonderService =
                BerichtenService(ObjectenApiService(ObjectsApiClient(TestTenants.register(zonder))), mock<DocumentenApiService>(), TestTenants.directory(zonder))

            assertThrows<ModuleNotConfiguredException> { zonderService.getBerichtenPage(inwoner, 1, 10, null) }

            assertThat(wijdeVeld.requestCount).isZero()
        }

    private fun tenant(
        oin: Oin,
        server: MockWebServer,
        type: String?,
    ) = TestTenants.tenant(
        oin,
        koppelingen = mapOf("objectenapi" to TestTenants.koppeling(server.url("/").toString().trimEnd('/'), oin = oin)),
        modules = if (type == null) emptyMap() else mapOf("berichten" to BerichtenConfiguratie(type)),
    )

    private fun leeg() = MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("""{"count":0,"results":[]}""")

    private companion object {
        const val BSN = "999990755"
        const val TYPE_BOSRAND = "https://objecttypes.example.nl/api/v2/objecttypes/bosrand-bericht"
        const val TYPE_WIJDE_VELD = "https://objecttypes.example.nl/api/v2/objecttypes/wijde-veld-bericht"
    }
}
