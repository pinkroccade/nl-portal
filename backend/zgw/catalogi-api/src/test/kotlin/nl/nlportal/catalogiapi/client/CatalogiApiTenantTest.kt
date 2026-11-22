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
package nl.nlportal.catalogiapi.client

import kotlinx.coroutines.test.runTest
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.TestTenants.BOSRAND
import nl.nlportal.core.testing.TestTenants.WIJDE_VELD
import nl.nlportal.core.util.ModuleNotConfiguredException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.UUID

/**
 * Seam 1 van epic #499 voor de Catalogi API: elke aanroep gaat alleen naar de koppeling van de tenant uit de context, met de
 * credentials van die koppeling.
 */
class CatalogiApiTenantTest {
    private lateinit var bosrandBron: MockWebServer
    private lateinit var wijdeVeldBron: MockWebServer

    @BeforeEach
    fun start() {
        bosrandBron = MockWebServer().apply { start() }
        wijdeVeldBron = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        listOf(bosrandBron, wijdeVeldBron).forEach { runCatching { it.shutdown() } }
    }

    private fun url(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    private fun client() =
        TestTenants
            .directory(
                TestTenants.tenant(BOSRAND, koppelingen = mapOf("catalogiapi" to TestTenants.zgwKoppeling(url(bosrandBron), "bosrand", "catalogi-secret"))),
                TestTenants.tenant(WIJDE_VELD, koppelingen = mapOf("catalogiapi" to TestTenants.zgwKoppeling(url(wijdeVeldBron), "wijdeveld", "catalogi-secret", oin = WIJDE_VELD))),
            ).let { CatalogiApiClient(TestTenants.register(*it.tenants.toTypedArray())) }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun clientClaim(authorization: String?): String? {
        val payload = authorization!!.removePrefix("Bearer ").split(".")[1]
        return Regex(""""client_id":"([^"]+)"""").find(String(Base64.getUrlDecoder().decode(payload)))?.groupValues?.get(1)
    }

    @Test
    fun `een zaaktype wordt alleen bij de koppeling van de eigen tenant opgehaald, met de credentials van die koppeling`() =
        runTest {
            val client = client()
            val id = UUID.randomUUID()
            bosrandBron.enqueue(json("""{"identificatie":"B-1","omschrijving":"Bosrand zaaktype"}"""))
            wijdeVeldBron.enqueue(json("""{"identificatie":"W-1","omschrijving":"Wijde Veld zaaktype"}"""))

            val bosrand = TestTenants.alsTenant(BOSRAND) { client.getZaakType(id) }

            assertThat(bosrand.identificatie).isEqualTo("B-1")
            assertThat(wijdeVeldBron.requestCount).isZero()
            val verzoek = bosrandBron.takeRequest()
            assertThat(verzoek.path).isEqualTo("/catalogi/api/v1/zaaktypen/$id")
            assertThat(verzoek.getHeader("Accept-Crs")).isEqualTo("EPSG:4326")
            assertThat(clientClaim(verzoek.getHeader("Authorization"))).isEqualTo("bosrand")

            val wijdeVeld = TestTenants.alsTenant(WIJDE_VELD) { client.getZaakType(id) }

            assertThat(wijdeVeld.identificatie).isEqualTo("W-1")
            assertThat(bosrandBron.requestCount).isEqualTo(1)
            assertThat(clientClaim(wijdeVeldBron.takeRequest().getHeader("Authorization"))).isEqualTo("wijdeveld")
        }

    @Test
    fun `statustypen en besluittypen vragen op zaaktype en gaan alleen naar de eigen tenant`() =
        runTest {
            val client = client()
            val zaaktype = "https://catalogi.example.nl/catalogi/api/v1/zaaktypen/${UUID.randomUUID()}"
            wijdeVeldBron.enqueue(json("""{"count":0,"next":null,"previous":null,"results":[]}"""))
            wijdeVeldBron.enqueue(json("""{"count":0,"next":null,"previous":null,"results":[]}"""))

            TestTenants.alsTenant(WIJDE_VELD) {
                client.getStatusTypes(zaaktype)
                client.getBesluitTypes(zaaktype)
            }

            assertThat(bosrandBron.requestCount).isZero()
            val statustypen = wijdeVeldBron.takeRequest().requestUrl!!
            assertThat(statustypen.encodedPath).isEqualTo("/catalogi/api/v1/statustypen")
            assertThat(statustypen.queryParameter("zaaktype")).isEqualTo(zaaktype)
            assertThat(statustypen.queryParameter("status")).isEqualTo("definitief")
            assertThat(wijdeVeldBron.takeRequest().requestUrl!!.queryParameter("zaaktype")).isEqualTo(zaaktype)
        }

    @Test
    fun `de zaaktype-URL voor een filter volgt de koppeling van de tenant`() =
        runTest {
            val client = client()
            val id = UUID.randomUUID()

            assertThat(TestTenants.alsTenant(BOSRAND) { client.zaaktypeUrl(id) }).isEqualTo("${url(bosrandBron)}/catalogi/api/v1/zaaktypen/$id")
            assertThat(TestTenants.alsTenant(WIJDE_VELD) { client.zaaktypeUrl(id) }).isEqualTo("${url(wijdeVeldBron)}/catalogi/api/v1/zaaktypen/$id")
        }

    @Test
    fun `een tenant zonder koppeling roept niets aan en faalt gesloten`() =
        runTest {
            val register = TestTenants.register(TestTenants.tenant(WIJDE_VELD), TestTenants.tenant(BOSRAND, koppelingen = mapOf("catalogiapi" to TestTenants.zgwKoppeling(url(bosrandBron), "bosrand", "catalogi-secret"))))
            val client = CatalogiApiClient(register)

            TestTenants.alsTenant(WIJDE_VELD) {
                assertThrows<ModuleNotConfiguredException> { client.getZaakType(UUID.randomUUID()) }
                assertThrows<ModuleNotConfiguredException> { client.zaaktypeUrl(UUID.randomUUID()) }
            }

            assertThat(bosrandBron.requestCount).isZero()
        }

    @Test
    fun `een 404 van de bron komt als KoppelingException zonder de URL van de bron te noemen`() =
        runTest {
            bosrandBron.enqueue(MockResponse().setResponseCode(404))

            val fout = TestTenants.alsTenant(BOSRAND) { assertThrows<KoppelingException> { client().getZaakType(UUID.randomUUID()) } }

            assertThat(fout.correlatieId).isNotBlank()
        }
}