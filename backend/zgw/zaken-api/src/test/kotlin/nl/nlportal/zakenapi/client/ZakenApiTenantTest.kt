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
import nl.nlportal.catalogiapi.client.CatalogiApiClient
import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.modulestatus.ModuleState
import nl.nlportal.core.modulestatus.ModuleStatusRegistry
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.TestTenants.BOSRAND
import nl.nlportal.core.testing.TestTenants.WIJDE_VELD
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zakenapi.service.ZakenApiService
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
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
    private val BSN_PARAMETER = "rol__betrokkeneIdentificatie__natuurlijkPersoon__inpBsn"
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
        count: Int = 0,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"count":$count,"next":${next?.let { "\"$it\"" }},"previous":null,"results":[$results]}""")

    private fun tenant(
        oin: nl.nlportal.core.tenancy.Oin,
        bron: MockWebServer,
        clientId: String,
        configuratie: ZakenApiConfiguratie = ZakenApiConfiguratie(),
    ): TenantDefinition =
        TestTenants.tenant(
            oin,
            koppelingen =
                mapOf(
                    "zakenapi" to TestTenants.zgwKoppeling(url(bron), clientId, "zaken-secret", oin = oin),
                    "catalogiapi" to TestTenants.zgwKoppeling(catalogiUrl(oin), clientId, "catalogi-secret", oin = oin),
                ),
            modules = mapOf("zakenapi" to configuratie),
        )

    private fun client(
        vararg tenants: TenantDefinition,
        status: ModuleStatusRegistry = ModuleStatusRegistry(),
    ): ZakenApiClient {
        val register = TestTenants.register(*tenants)
        return ZakenApiClient(register, TestTenants.directory(*tenants), CatalogiApiClient(register), status)
    }

    /** Elke tenant heeft een eigen Catalogi API; de URL is alleen een filterwaarde, de tests roepen hem niet aan. */
    private fun catalogiUrl(oin: nl.nlportal.core.tenancy.Oin) = "https://catalogi-${oin.value}.example.nl"

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
            assertThat(verzoek.getHeader("Content-Type")).startsWith("application/json")
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
            assertThat(wijdeVeldBron.takeRequest().body.readUtf8()).isEqualTo("""{"$BSN_PARAMETER":["$bsn"]}""")
        }

    @Test
    fun `het BSN van een GET-vraag en van de rollen gaat als query alleen naar de koppeling van de eigen tenant`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"), tenant(WIJDE_VELD, wijdeVeldBron, "wijdeveld"))
            val burger = JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()
            repeat(2) { bosrandBron.enqueue(leeg()) }
            wijdeVeldBron.enqueue(leeg())

            TestTenants.alsTenant(BOSRAND) {
                client
                    .zaken()
                    .search()
                    .withBsn(bsn)
                    .retrieve()
                client
                    .zaakRollen()
                    .search()
                    .forZaak(UUID.randomUUID())
                    .withAuthentication(burger)
                    .retrieveAll()
            }

            assertThat(wijdeVeldBron.requestCount).isZero()
            assertThat(bosrandBron.takeRequest().requestUrl?.queryParameter(BSN_PARAMETER)).isEqualTo(bsn)
            assertThat(bosrandBron.takeRequest().requestUrl?.query).contains(bsn)

            TestTenants.alsTenant(WIJDE_VELD) {
                client
                    .zaken()
                    .search()
                    .withBsn(bsn)
                    .retrieve()
            }

            assertThat(bosrandBron.requestCount).isEqualTo(2)
            assertThat(wijdeVeldBron.takeRequest().requestUrl?.queryParameter(BSN_PARAMETER)).isEqualTo(bsn)
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
            val machtigingen = mock<AuthenticationMachtigingsDienstService> { on { zaakTypes(any()) } doReturn null }
            val service = ZakenApiService(client, mock<DocumentenApiService>(), mock<ObjectenApiService>(), machtigingen)
            bosrandBron.enqueue(leeg())
            wijdeVeldBron.enqueue(leeg())
            val burger = JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()

            TestTenants.alsTenant(BOSRAND) { service.getZaken(1, authentication = burger) }
            TestTenants.alsTenant(WIJDE_VELD) { service.getZaken(1, authentication = burger) }

            assertThat(bosrandBron.takeRequest().body.readUtf8())
                .isEqualTo("""{"$BSN_PARAMETER":["$bsn"],"rol__omschrijvingGeneriek":["initiator"],"zaaktype__not_in":["${catalogiUrl(BOSRAND)}/catalogi/api/v1/zaaktypen/$uitgesloten"]}""")
            assertThat(wijdeVeldBron.takeRequest().body.readUtf8()).doesNotContain("zaaktype__not_in")
        }

    @Test
    fun `een zaak van een uitgesloten zaaktype komt niet in de lijst, ook niet als de bron het filter niet toepast`() =
        runTest {
            val uitgesloten = "c2d3e114-bbe4-478d-80de-e8a8c8f8a948"
            val toegestaan = "5f1c9a3e-2b7d-4c58-9d6a-0e8f4b1a7c33"
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand", ZakenApiConfiguratie(uitgeslotenZaaktypen = listOf(uitgesloten))))
            val service = ZakenApiService(client, mock<DocumentenApiService>(), mock<ObjectenApiService>(), mock<AuthenticationMachtigingsDienstService>())

            // De bron heeft een eigen Catalogi: de zaaktype-URL's in het filter zaaktype__not_in komen daar niet mee overeen.
            fun zaak(
                nummer: Int,
                zaaktype: String,
            ) = """{"uuid":"a1b2c3d4-0000-4000-8000-00000000000$nummer","url":"https://eigen.example.nl/zaken/api/v1/zaken/a1b2c3d4-0000-4000-8000-00000000000$nummer",""" +
                """"identificatie":"Z$nummer","omschrijving":"o","zaaktype":"https://eigen-catalogi.example.nl/catalogi/api/v1/zaaktypen/$zaaktype",""" +
                """"startdatum":"2026-01-01","einddatum":null,"status":null}"""
            bosrandBron.enqueue(pagina(listOf(zaak(1, uitgesloten), zaak(2, toegestaan), zaak(3, uitgesloten.uppercase())).joinToString(","), count = 3))

            val resultaat = TestTenants.alsTenant(BOSRAND) { service.getZaken(1, authentication = JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()) }

            assertThat(resultaat.content.map { it.identificatie }).containsExactly("Z2")
            assertThat(resultaat.totalElements).isEqualTo(1)
        }

    @Test
    fun `het filter op uitgesloten zaaktypen gebruikt de Catalogi-koppeling van elke tenant`() =
        runTest {
            val uitgesloten = "c2d3e114-bbe4-478d-80de-e8a8c8f8a948"
            val configuratie = ZakenApiConfiguratie(uitgeslotenZaaktypen = listOf(uitgesloten))
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand", configuratie), tenant(WIJDE_VELD, wijdeVeldBron, "wijdeveld", configuratie))
            val machtigingen = mock<AuthenticationMachtigingsDienstService> { on { zaakTypes(any()) } doReturn null }
            val service = ZakenApiService(client, mock<DocumentenApiService>(), mock<ObjectenApiService>(), machtigingen)
            bosrandBron.enqueue(leeg())
            wijdeVeldBron.enqueue(leeg())
            val burger = JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()

            TestTenants.alsTenant(BOSRAND) { service.getZaken(1, authentication = burger) }
            TestTenants.alsTenant(WIJDE_VELD) { service.getZaken(1, authentication = burger) }

            assertThat(bosrandBron.takeRequest().body.readUtf8()).contains("\"${catalogiUrl(BOSRAND)}/catalogi/api/v1/zaaktypen/$uitgesloten\"").doesNotContain(catalogiUrl(WIJDE_VELD))
            assertThat(wijdeVeldBron.takeRequest().body.readUtf8()).contains("\"${catalogiUrl(WIJDE_VELD)}/catalogi/api/v1/zaaktypen/$uitgesloten\"").doesNotContain(catalogiUrl(BOSRAND))
        }

    @Test
    fun `een zaak van een uitgesloten zaaktype geeft niet gevonden, ook als de bron de zaak teruggeeft`() =
        runTest {
            val uitgesloten = "c2d3e114-bbe4-478d-80de-e8a8c8f8a948"
            val toegestaan = "5f1c9a3e-2b7d-4c58-9d6a-0e8f4b1a7c33"
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand", ZakenApiConfiguratie(uitgeslotenZaaktypen = listOf(uitgesloten))))
            val machtigingen = mock<AuthenticationMachtigingsDienstService> { on { isAllowedZaakType(any(), any()) } doReturn true }
            val service = ZakenApiService(client, mock<DocumentenApiService>(), mock<ObjectenApiService>(), machtigingen)
            val zaakId = UUID.randomUUID()
            val burger = JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()

            fun antwoorden(zaaktype: String) =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        if (request.requestUrl!!.encodedPath.endsWith("/rollen")) {
                            pagina("""{"uuid":"a1b2c3d4-0000-4000-8000-000000000001","zaak":"x"}""", count = 1)
                        } else {
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{"uuid":"$zaakId","url":"https://eigen.example.nl/zaken/$zaakId","identificatie":"Z1","omschrijving":"o",""" +
                                        """"zaaktype":"https://eigen-catalogi.example.nl/catalogi/api/v1/zaaktypen/$zaaktype","startdatum":"2026-01-01","einddatum":null,"status":null}""",
                                )
                        }
                }

            bosrandBron.dispatcher = antwoorden(uitgesloten.uppercase())
            val fout = TestTenants.alsTenant(BOSRAND) { assertThrows<ResponseStatusException> { service.getZaak(zaakId, burger) } }
            assertThat(fout.statusCode.value()).isEqualTo(404)

            bosrandBron.dispatcher = antwoorden(toegestaan)
            assertThat(TestTenants.alsTenant(BOSRAND) { service.getZaak(zaakId, burger) }.identificatie).isEqualTo("Z1")
        }

    @Test
    fun `een bron die altijd een volgende pagina geeft levert na het maximum aan verzoeken een KoppelingException`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"))
            bosrandBron.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = pagina("", next = "https://onbelangrijk.example/volgende")
                }

            val fout =
                TestTenants.alsTenant(BOSRAND) {
                    assertThrows<KoppelingException> {
                        client
                            .zoeken()
                            .search()
                            .withBsn(bsn)
                            .retrieveAll()
                    }
                }

            assertThat(fout.soort).isEqualTo(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR)
            assertThat(fout.correlatieId).isNotBlank()
            assertThat(bosrandBron.requestCount).isEqualTo(100)
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
            val objecten = ObjectenApiService(ObjectsApiClient(TestTenants.register(tenant(BOSRAND, bosrandBron, "bosrand").let { it.copy(koppelingen = it.koppelingen + ("objectenapi" to TestTenants.koppeling("https://objecten.example.nl"))) })))
            val service = ZakenApiService(client, mock<DocumentenApiService>(), objecten, mock<AuthenticationMachtigingsDienstService>())
            val zaakObject =
                """{"url":"http://z/1","uuid":"1","zaak":"http://z/zaak","object":"https://evil.example/api/v2/objects/1","objectType":"overige","objectTypeOverige":"portaalzaakdetails"}"""
            bosrandBron.enqueue(pagina(zaakObject))

            val zaakUrl = "https://zaken.example.nl/zaken/api/v1/zaken/2941048a-7290-4a78-8c4e-14feaec137dc"
            val fout = TestTenants.alsTenant(BOSRAND) { assertThrows<KoppelingException> { service.getZaakDetails(zaakUrl) } }

            assertThat(fout.soort).isEqualTo(nl.nlportal.core.koppeling.KoppelingFoutsoort.HOST_BINDING)
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

            TestTenants.alsTenant(BOSRAND) { assertThrows<KoppelingException> { client.zaken().get(UUID.randomUUID()).retrieve() } }

            assertThat(status.snapshot().single().status).isEqualTo(ModuleState.DEGRADED)
        }

    private fun zaakBron(
        zaakId: UUID,
        zaaktypeUrl: String,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val pad = request.requestUrl!!.encodedPath
            return when {
                pad.endsWith("/rollen") -> {
                    pagina("""{"uuid":"a1b2c3d4-0000-4000-8000-000000000001","zaak":"x"}""", count = 1)
                }

                pad.contains("/zaakinformatieobjecten/") -> {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"uuid":"$ZAAKDOCUMENT_ID","informatieobject":"https://eigen.example.nl/documenten/api/v1/enkelvoudiginformatieobjecten/$ZAAKDOCUMENT_ID","zaak":"https://eigen.example.nl/zaken/api/v1/zaken/$zaakId"}""")
                }

                else -> {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"uuid":"$zaakId","url":"https://eigen.example.nl/zaken/$zaakId","identificatie":"Z1","omschrijving":"o",""" +
                                """"zaaktype":"$zaaktypeUrl","startdatum":"2026-01-01","einddatum":null,"status":null}""",
                        )
                }
            }
        }
    }

    @Test
    fun `een zaaktype-URL zonder UUID telt bij een directe aanvraag als uitgesloten`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand", ZakenApiConfiguratie(uitgeslotenZaaktypen = listOf("c2d3e114-bbe4-478d-80de-e8a8c8f8a948"))))
            val machtigingen = mock<AuthenticationMachtigingsDienstService> { on { isAllowedZaakType(any(), any()) } doReturn true }
            val service = ZakenApiService(client, mock<DocumentenApiService>(), mock<ObjectenApiService>(), machtigingen)
            val zaakId = UUID.randomUUID()
            bosrandBron.dispatcher = zaakBron(zaakId, "https://eigen-catalogi.example.nl/catalogi/api/v1/zaaktypen/geen-uuid")

            val fout =
                TestTenants.alsTenant(BOSRAND) {
                    assertThrows<ResponseStatusException> { service.getZaak(zaakId, JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()) }
                }

            assertThat(fout.statusCode.value()).isEqualTo(404)
        }

    @Test
    fun `een zaaktype-URL zonder UUID komt niet in de lijst als er zaaktypen zijn uitgesloten`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand", ZakenApiConfiguratie(uitgeslotenZaaktypen = listOf("c2d3e114-bbe4-478d-80de-e8a8c8f8a948"))))
            val service = ZakenApiService(client, mock<DocumentenApiService>(), mock<ObjectenApiService>(), mock<AuthenticationMachtigingsDienstService>())
            val zaak =
                """{"uuid":"a1b2c3d4-0000-4000-8000-000000000001","url":"https://eigen.example.nl/zaken/api/v1/zaken/a1b2c3d4-0000-4000-8000-000000000001",""" +
                    """"identificatie":"Z1","omschrijving":"o","zaaktype":"https://eigen-catalogi.example.nl/catalogi/api/v1/zaaktypen/geen-uuid",""" +
                    """"startdatum":"2026-01-01","einddatum":null,"status":null}"""
            bosrandBron.enqueue(pagina(zaak, count = 1))

            val resultaat = TestTenants.alsTenant(BOSRAND) { service.getZaken(1, authentication = JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()) }

            assertThat(resultaat.content).isEmpty()
        }

    @Test
    fun `de inhoud van een document bij een zaak van een uitgesloten zaaktype is niet te downloaden`() =
        runTest {
            val uitgesloten = "c2d3e114-bbe4-478d-80de-e8a8c8f8a948"
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand", ZakenApiConfiguratie(uitgeslotenZaaktypen = listOf(uitgesloten))))
            val machtigingen = mock<AuthenticationMachtigingsDienstService> { on { isAllowedZaakType(any(), any()) } doReturn true }
            val documenten = mock<DocumentenApiService>()
            val service = ZakenApiService(client, documenten, mock<ObjectenApiService>(), machtigingen)
            bosrandBron.dispatcher = zaakBron(UUID.randomUUID(), "https://eigen-catalogi.example.nl/catalogi/api/v1/zaaktypen/$uitgesloten")

            val fout =
                TestTenants.alsTenant(BOSRAND) {
                    assertThrows<ResponseStatusException> { service.getZaakDocumentContent(ZAAKDOCUMENT_ID, JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()) }
                }

            assertThat(fout.statusCode.value()).isEqualTo(404)
            verify(documenten, never()).getDocument(any())
            verify(documenten, never()).getDocumentContentStreaming(any())
        }

    @Test
    fun `de inhoud van een document bij een zaaktype waarvoor de gebruiker niet is gemachtigd is niet te downloaden`() =
        runTest {
            val client = client(tenant(BOSRAND, bosrandBron, "bosrand"))
            val machtigingen = mock<AuthenticationMachtigingsDienstService> { on { isAllowedZaakType(any(), any()) } doReturn false }
            val documenten = mock<DocumentenApiService>()
            val service = ZakenApiService(client, documenten, mock<ObjectenApiService>(), machtigingen)
            bosrandBron.dispatcher = zaakBron(UUID.randomUUID(), "https://eigen-catalogi.example.nl/catalogi/api/v1/zaaktypen/5f1c9a3e-2b7d-4c58-9d6a-0e8f4b1a7c33")

            val fout =
                TestTenants.alsTenant(BOSRAND) {
                    assertThrows<ResponseStatusException> { service.getZaakDocumentContent(ZAAKDOCUMENT_ID, JwtBuilder().vertegenwoordigdeBsn(bsn).buildAuthentication()) }
                }

            assertThat(fout.statusCode.value()).isEqualTo(401)
            verify(documenten, never()).getDocumentContentStreaming(any())
        }

    private companion object {
        const val ZAAKDOCUMENT_ID = "9d3c2f64-6a1b-4e7a-8c55-1f0b7a2d4e90"
    }
}