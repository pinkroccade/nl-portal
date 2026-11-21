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
package nl.nlportal.zakenapi.client

import kotlinx.coroutines.test.runTest
import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.modulestatus.ModuleState
import nl.nlportal.core.modulestatus.ModuleStatusRegistry
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.TestTenants.BOSRAND
import nl.nlportal.core.testing.TestTenants.WIJDE_VELD
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zakenapi.service.ZakenApiService
import nl.nlportal.zgw.objectenapi.autoconfiguration.ObjectsApiClientConfig.ObjectsApiClientConfigProperties
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.net.URI
import java.util.Base64
import java.util.UUID

/**
 * Seam 1 van epic #499 voor de Zaken API: wat de module naar de bron stuurt, per tenant. Het BSN, de credentials en de
 * modulegegevens van een tenant bereiken alleen de koppeling van die tenant.
 */
class ZakenApiTenantTest {
    private val bsn = "123456782"
    private lateinit var bosrandBron: MockWebServer
    private lateinit var wijdeVeldBron: MockWebServer
    private lateinit var buitenstaander: MockWebServer

    @BeforeEach
    fun start() {
        bosrandBron = MockWebServer().apply { start() }
        wijdeVeldBron = MockWebServer().apply { start() }
        buitenstaander = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        listOf(bosrandBron, wijdeVeldBron, buitenstaander).forEach { runCatching { it.shutdown() } }
    }

    private fun url(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    private fun leeg() = pagina("")

    private fun pagina(
        results: String,
        next: String? = null,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"count":0,"next":${next?.let { "\"$it\"" }},"previous":null,"results":[$results]}""")

    private fun tenant(
        oin: nl.nlportal.core.tenancy.Oin,
        bron: MockWebServer,
        clientId: String,
        configuratie: ZakenApiConfiguratie = ZakenApiConfiguratie(),
    ): TenantDefinition {
        val auth = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = clientId, secret = TestTenants.geheim("zaken-secret", oin))
        return TestTenants.tenant(
            oin,
            koppelingen = mapOf("zakenapi" to TestTenants.koppeling(url(bron), auth, oin = oin)),
            modules = mapOf("zakenapi" to configuratie),
        )
    }

    private fun client(
        vararg tenants: TenantDefinition,
        status: ModuleStatusRegistry = ModuleStatusRegistry(),
    ) = ZakenApiClient(TestTenants.register(*tenants), TestTenants.directory(*tenants), { "https://catalogi.example.nl/catalogi/api/v1/zaaktypen/$it" }, status)

    private fun clientClaim(authorization: String?): String? {
        val payload = authorization!!.removePrefix("Bearer ").split(".")[1]
        return Regex(""""client_id":"([^"]+)"""").find(String(Base64.getUrlDecoder().decode(payload)))?.groupValues?.get(1)
    }

    @Test
    fun `het BSN gaat alleen naar de koppeling van de eigen tenant`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"), tenant(WIJDE_VELD, wijdeVeldBron, "wijdeveld"))
            bosrandBron.enqueue(leeg())
            wijdeVeldBron.enqueue(leeg())

            TestTenants.alsTenant(BOSRAND) {
                client
                    .zoeken()
                    .search()
                    .withAuthentication(JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication())
                    .retrieve()
            }

            assertThat(bosrandBron.requestCount).isEqualTo(1)
            assertThat(wijdeVeldBron.requestCount).isZero()
            val verzoek = bosrandBron.takeRequest()
            assertThat(verzoek.headers.toString()).contains("Content-Type: application/json")
            assertThat(verzoek.body.readUtf8()).isEqualTo("""{"rol__betrokkeneIdentificatie__natuurlijkPersoon__inpBsn":["$bsn"]}""")

            TestTenants.alsTenant(WIJDE_VELD) {
                client
                    .zoeken()
                    .search()
                    .withAuthentication(JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication())
                    .retrieve()
            }

            assertThat(wijdeVeldBron.requestCount).isEqualTo(1)
            assertThat(bosrandBron.requestCount).isEqualTo(1)
            assertThat(buitenstaander.requestCount).isZero()
        }

    @Test
    fun `elke tenant gebruikt de credentials van zijn eigen koppeling`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"), tenant(WIJDE_VELD, wijdeVeldBron, "wijdeveld"))
            bosrandBron.enqueue(leeg())
            wijdeVeldBron.enqueue(leeg())

            TestTenants.alsTenant(BOSRAND) { client.zaken().search().retrieve() }
            TestTenants.alsTenant(WIJDE_VELD) { client.zaken().search().retrieve() }

            assertThat(clientClaim(bosrandBron.takeRequest().getHeader("Authorization"))).isEqualTo("bosrand")
            assertThat(clientClaim(wijdeVeldBron.takeRequest().getHeader("Authorization"))).isEqualTo("wijdeveld")
        }

    @Test
    fun `een tenant zonder koppeling of modulegegevens roept niets aan, ook niet de bron van een andere tenant`() =
        runTest {
            val zonder = TestTenants.tenant(WIJDE_VELD)
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"), zonder)

            TestTenants.alsTenant(WIJDE_VELD) {
                assertThrows<nl.nlportal.core.util.ModuleNotConfiguredException> {
                    client
                        .zoeken()
                        .search()
                        .withBsn(bsn)
                        .retrieve()
                }
            }

            assertThat(bosrandBron.requestCount).isZero()
        }

    @Test
    fun `de uitgesloten zaaktypen volgen de tenant`() =
        runTest {
            val uitgesloten = "c2d3e114-bbe4-478d-80de-e8a8c8f8a948"
            val client =
                client(
                    tenant(BOSRAND, bosrandBron, "bosrand", ZakenApiConfiguratie(uitgeslotenZaaktypen = listOf(uitgesloten))),
                    tenant(WIJDE_VELD, wijdeVeldBron, "wijdeveld"),
                )
            val service = ZakenApiService(client, mock<DocumentenApiService>(), mock<ObjectenApiService>(), mock<AuthenticationMachtigingsDienstService>())
            bosrandBron.enqueue(leeg())
            wijdeVeldBron.enqueue(leeg())
            val burger = JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()

            TestTenants.alsTenant(BOSRAND) { service.getZaken(1, authentication = burger) }
            TestTenants.alsTenant(WIJDE_VELD) { service.getZaken(1, authentication = burger) }

            assertThat(bosrandBron.takeRequest().body.readUtf8()).contains("zaaktype__not_in", "https://catalogi.example.nl/catalogi/api/v1/zaaktypen/$uitgesloten")
            assertThat(wijdeVeldBron.takeRequest().body.readUtf8()).doesNotContain("zaaktype__not_in")
        }

    @Test
    fun `een volgende pagina op een andere host wordt niet gevolgd, ook niet met het token van de koppeling`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"))
            bosrandBron.enqueue(pagina("", next = "${url(buitenstaander)}/zaken/api/v1/zaken?page=2"))

            val fout =
                TestTenants.alsTenant(BOSRAND) {
                    assertThrows<KoppelingException> {
                        client
                            .zaken()
                            .search()
                            .withBsn(bsn)
                            .retrieveAll()
                    }
                }

            assertThat(fout.soort).isEqualTo(KoppelingFoutsoort.HOST_BINDING)
            assertThat(buitenstaander.requestCount).isZero()
            assertThat(bosrandBron.requestCount).isEqualTo(1)
        }

    @Test
    fun `een zoekvraag haalt alle paginas op via het paginanummer en volgt geen URL van de bron`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"))
            bosrandBron.enqueue(pagina("", next = "${url(buitenstaander)}/zaken/api/v1/zaken/_zoek?page=2"))
            bosrandBron.enqueue(leeg())

            TestTenants.alsTenant(BOSRAND) {
                client
                    .zoeken()
                    .search()
                    .withBsn(bsn)
                    .pageSize(1)
                    .retrieveAll()
            }

            assertThat(bosrandBron.takeRequest().path).isEqualTo("/zaken/api/v1/zaken/_zoek?page=1&pageSize=1")
            assertThat(bosrandBron.takeRequest().path).isEqualTo("/zaken/api/v1/zaken/_zoek?page=2&pageSize=1")
            assertThat(buitenstaander.requestCount).isZero()
        }

    @Test
    fun `een object-URL uit een antwoord van de Zaken API op een andere host wordt niet gevolgd, ook niet met het token van de Objecten API`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"))
            val objectsApiClient = mock<ObjectsApiClient>()
            val objecten = ObjectenApiService(objectsApiClient, ObjectsApiClientConfigProperties(URI.create("https://objecten.example.nl"), "token"))
            val service = ZakenApiService(client, mock<DocumentenApiService>(), objecten, mock<AuthenticationMachtigingsDienstService>())
            val zaakObject =
                """{"url":"http://z/1","uuid":"1","zaak":"http://z/zaak","object":"https://evil.example/api/v2/objects/1","objectType":"overige","objectTypeOverige":"portaalzaakdetails"}"""
            bosrandBron.enqueue(pagina(zaakObject))

            val zaakUrl = "https://zaken.example.nl/zaken/api/v1/zaken/2941048a-7290-4a78-8c4e-14feaec137dc"
            TestTenants.alsTenant(BOSRAND) { assertThrows<IllegalArgumentException> { service.getZaakDetails(zaakUrl) } }

            verify(objectsApiClient, never()).webClientWithoutBaseUrl()
        }

    @Test
    fun `substatussen falen gesloten bij ontbrekende configuratie of tenant, geen stille lege lijst`() =
        runTest {
            val service = ZakenApiService(client(TestTenants.tenant(WIJDE_VELD)), mock<DocumentenApiService>(), mock<ObjectenApiService>(), mock<AuthenticationMachtigingsDienstService>())

            TestTenants.alsTenant(WIJDE_VELD) { assertThrows<nl.nlportal.core.util.ModuleNotConfiguredException> { service.getZaakSubStatussen("http://z/zaak", "http://z/status") } }
            assertThrows<nl.nlportal.core.tenancy.TenantContextMissingException> { service.getZaakSubStatussen("http://z/zaak", "http://z/status") }
        }

    @Test
    fun `substatussen blijven bij een storing van de bron een lege lijst`() =
        runTest {
            val service = ZakenApiService(client(tenant(BOSRAND, bosrandBron, "bosrand")), mock<DocumentenApiService>(), mock<ObjectenApiService>(), mock<AuthenticationMachtigingsDienstService>())
            bosrandBron.enqueue(MockResponse().setResponseCode(503))

            assertThat(TestTenants.alsTenant(BOSRAND) { service.getZaakSubStatussen("http://z/zaak", "http://z/status") }).isEmpty()
        }

    @Test
    fun `een bron die niet antwoordt zet de modulestatus op DEGRADED`() =
        runTest {
            val status = ModuleStatusRegistry()
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"), status = status)
            bosrandBron.shutdown()

            TestTenants.alsTenant(BOSRAND) { runCatching { client.zaken().get(UUID.randomUUID()).retrieve() } }

            assertThat(status.snapshot().single().status).isEqualTo(ModuleState.DEGRADED)
        }
}