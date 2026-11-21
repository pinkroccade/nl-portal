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
package nl.nlportal.documentenapi.service

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.documentenapi.TestHelper
import nl.nlportal.documentenapi.client.DocumentenApiClient
import nl.nlportal.documentenapi.configuratie.DocumentApiConfiguratie
import nl.nlportal.documentenapi.configuratie.DocumentenApiConfiguratie
import nl.nlportal.documentenapi.domain.Document
import nl.nlportal.documentenapi.domain.DocumentStatus
import nl.nlportal.documentenapi.domain.Vertrouwelijkheid
import nl.nlportal.documentenapi.exceptions.MimeTypeDeniedException
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import java.util.UUID

/**
 * Seam 1 van epic #499 voor de Documenten API: de service tegen nep-bronnen, met koppelingen en `modules.documenten-api` per tenant
 * en het echte koppelingsregister van core.
 */
class DocumentenApiServiceTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD
    private val documentId = UUID.fromString("095be615-a8ad-4c33-8e9c-c7612fbf6c9f")

    private lateinit var bosrandOpenZaak: MockWebServer
    private lateinit var bosrandArchief: MockWebServer
    private lateinit var wijdeVeldOpenZaak: MockWebServer

    @BeforeEach
    fun start() {
        bosrandOpenZaak = MockWebServer().apply { start() }
        bosrandArchief = MockWebServer().apply { start() }
        wijdeVeldOpenZaak = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        listOf(bosrandOpenZaak, bosrandArchief, wijdeVeldOpenZaak).forEach { runCatching { it.shutdown() } }
    }

    private fun basis(server: MockWebServer) = server.url("/documenten/api/v1").toString()

    private fun documentUrl(
        server: MockWebServer,
        id: UUID = documentId,
    ) = "${basis(server)}/enkelvoudiginformatieobjecten/$id"

    private fun tenant(
        oin: Oin,
        bronnen: Map<String, MockWebServer>,
        standaard: String = bronnen.keys.first(),
        aanpassen: (DocumentenApiConfiguratie) -> DocumentenApiConfiguratie = { it },
    ): TenantDefinition =
        TestTenants.tenant(
            oin = oin,
            koppelingen =
                bronnen.mapValues { (_, server) ->
                    TestTenants.koppeling(
                        basis(server),
                        auth = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "nl-portal", secret = TestTenants.geheim("documenten-secret", oin)),
                        oin = oin,
                    )
                },
            modules =
                mapOf(
                    "documenten-api" to
                        aanpassen(
                            DocumentenApiConfiguratie(
                                standaardDocumentApi = standaard,
                                documentApis = bronnen.mapValues { DocumentApiConfiguratie(rsin = "051845623", documentTypeUrl = "https://catalogi.example/type") },
                            ),
                        ),
                ),
        )

    private fun service(vararg tenants: TenantDefinition) = DocumentenApiService(DocumentenApiClient(TestTenants.register(*tenants)), TenantDirectory(tenants.toList()))

    private fun <T> alsTenant(
        oin: Oin,
        authenticatie: PortalAuthentication? = null,
        blok: suspend () -> T,
    ): T {
        val context =
            TestTenants.tenantContext(oin).let {
                if (authenticatie == null) it else it.putAll(ReactiveSecurityContextHolder.withAuthentication(authenticatie).readOnly())
            }
        return runBlocking { withContext(ReactorContext(context)) { blok() } }
    }

    private fun json(bestand: String = "/data/get-enkelvoudiginformatieobject-response.json") = TestHelper.mockResponseFromFile(bestand)

    // Ophalen

    @Test
    fun `een document komt van de koppeling van de tenant met een ZGW-JWT en draagt de naam van de document-API`() {
        bosrandOpenZaak.enqueue(json())
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        val document = alsTenant(bosrand) { service.getDocument(documentId, "openzaak") }

        assertThat(document.titel).isEqualTo("Een titel")
        assertThat(document.documentapi).isEqualTo("openzaak")
        val verzoek = bosrandOpenZaak.takeRequest()
        assertThat(verzoek.path).isEqualTo("/documenten/api/v1/enkelvoudiginformatieobjecten/$documentId")
        assertThat(verzoek.getHeader("Authorization")).startsWith("Bearer ey")
        assertThat(verzoek.getHeader("Accept-Crs")).isEqualTo("EPSG:4326")
    }

    @Test
    fun `een onbekende document-API weigert duidelijk`() {
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        assertThatThrownBy { alsTenant(bosrand) { service.getDocument(documentId, "bestaat-niet") } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThat(bosrandOpenZaak.requestCount).isZero()
    }

    @Test
    fun `een fout van de bron wordt een KoppelingException zonder de inhoud van de bron`() {
        bosrandOpenZaak.enqueue(MockResponse().setResponseCode(500).setBody("BSN 999990755 stack trace"))
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        val fout = runCatching { alsTenant(bosrand) { service.getDocument(documentId, "openzaak") } }.exceptionOrNull()

        assertThat(fout).isInstanceOf(KoppelingException::class.java)
        assertThat(fout!!.message).doesNotContain("999990755")
    }

    @Test
    fun `de inhoud van een document komt als stroom van de koppeling`() {
        bosrandOpenZaak.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("inhoud van het document"))
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        val tekst =
            alsTenant(bosrand) {
                service.getDocumentContentStreaming(documentUrl(bosrandOpenZaak)).toList().joinToString("") { buffer ->
                    val bytes = ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                    DataBufferUtils.release(buffer)
                    String(bytes)
                }
            }

        assertThat(tekst).isEqualTo("inhoud van het document")
        val verzoek = bosrandOpenZaak.takeRequest()
        assertThat(verzoek.path).isEqualTo("/documenten/api/v1/enkelvoudiginformatieobjecten/$documentId/download")
        assertThat(verzoek.getHeader("Accept")).isEqualTo("application/octet-stream")
        assertThat(verzoek.getHeader("Authorization")).startsWith("Bearer ey")
    }

    @Test
    fun `de inhoud van een document op id komt van de gekozen document-API`() {
        bosrandArchief.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("x"))
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak, "archief" to bosrandArchief)))

        alsTenant(bosrand) { service.getDocumentContentStreaming(documentId, "archief").toList().forEach { DataBufferUtils.release(it) } }

        assertThat(bosrandArchief.requestCount).isEqualTo(1)
        assertThat(bosrandOpenZaak.requestCount).isZero()
    }

    // Exacte match van een document-URL (hoog-risicopunt van T14)

    @Test
    fun `een document-URL kiest de document-API waar hij exact onder valt`() {
        bosrandArchief.enqueue(json())
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak, "archief" to bosrandArchief)))

        val document = alsTenant(bosrand) { service.getDocument(documentUrl(bosrandArchief)) }

        assertThat(document.documentapi).isEqualTo("archief")
        assertThat(bosrandArchief.requestCount).isEqualTo(1)
        assertThat(bosrandOpenZaak.requestCount).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "pad", "host", "padprefix", "puntsegment", "ander-schema", "gebruikersinfo"])
    fun `een URL die de basis-URL alleen bevat kiest geen document-API en er gaat niets over de lijn`(soort: String) {
        val bron = basis(bosrandOpenZaak)
        val poort = bosrandOpenZaak.port
        val url =
            when (soort) {
                "query" -> "https://kwaad.example/x?doel=$bron/enkelvoudiginformatieobjecten/$documentId"
                "pad" -> "https://kwaad.example/$bron/enkelvoudiginformatieobjecten/$documentId"
                "host" -> "http://localhost.kwaad.example:$poort/documenten/api/v1/enkelvoudiginformatieobjecten/$documentId"
                "padprefix" -> "http://localhost:$poort/documenten/api/v1extra/enkelvoudiginformatieobjecten/$documentId"
                "puntsegment" -> "http://localhost:$poort/documenten/api/v1/../v2/enkelvoudiginformatieobjecten/$documentId"
                "ander-schema" -> "https://localhost:$poort/documenten/api/v1/enkelvoudiginformatieobjecten/$documentId"
                else -> "http://gebruiker@localhost:$poort/documenten/api/v1/enkelvoudiginformatieobjecten/$documentId"
            }
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        assertThatThrownBy { alsTenant(bosrand) { service.getDocument(url) } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThatThrownBy { alsTenant(bosrand) { service.getDocumentContentStreaming(url) } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThat(bosrandOpenZaak.requestCount).isZero()
    }

    @Test
    fun `een document-URL van de koppeling van een andere tenant wordt geweigerd`() {
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)), tenant(wijdeVeld, mapOf("openzaak" to wijdeVeldOpenZaak)))

        assertThatThrownBy { alsTenant(bosrand) { service.getDocument(documentUrl(wijdeVeldOpenZaak)) } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThat(wijdeVeldOpenZaak.requestCount).isZero()
        assertThat(bosrandOpenZaak.requestCount).isZero()
    }

    @Test
    fun `twee document-APIs met dezelfde basis-URL zijn niet te onderscheiden en weigeren`() {
        val dubbel =
            TestTenants.tenant(
                oin = bosrand,
                koppelingen = mapOf("a" to TestTenants.koppeling(basis(bosrandOpenZaak)), "b" to TestTenants.koppeling(basis(bosrandOpenZaak))),
                modules = mapOf("documenten-api" to DocumentenApiConfiguratie("a", mapOf("a" to DocumentApiConfiguratie(), "b" to DocumentApiConfiguratie()))),
            )
        val service = service(dubbel)

        assertThatThrownBy { alsTenant(bosrand) { service.getDocument(documentUrl(bosrandOpenZaak)) } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThat(bosrandOpenZaak.requestCount).isZero()
    }

    // Seam 1: het BSN gaat alleen naar de koppeling van de eigen tenant

    @Test
    fun `het BSN van een upload gaat alleen naar de koppeling van de eigen tenant`() {
        bosrandOpenZaak.enqueue(json("/data/post-enkelvoudiginformatieobject-response.json"))
        wijdeVeldOpenZaak.enqueue(json("/data/post-enkelvoudiginformatieobject-response.json"))
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)), tenant(wijdeVeld, mapOf("openzaak" to wijdeVeldOpenZaak)))

        alsTenant(bosrand, JwtBuilder().vertegenwoordigdeBsn("999990755").buildAuthentication()) {
            service.uploadDocument("inhoud".toByteArray(), "bestand.txt", "openzaak")
        }

        assertThat(bosrandOpenZaak.takeRequest().body.readUtf8()).contains("\"auteur\":\"999990755\"")
        assertThat(wijdeVeldOpenZaak.requestCount).isZero()

        alsTenant(wijdeVeld, JwtBuilder().vertegenwoordigdeBsn("111222333").buildAuthentication()) {
            service.uploadDocument("inhoud".toByteArray(), "bestand.txt", "openzaak")
        }

        val verzoek = wijdeVeldOpenZaak.takeRequest().body.readUtf8()
        assertThat(verzoek).contains("\"auteur\":\"111222333\"").doesNotContain("999990755")
        assertThat(bosrandOpenZaak.requestCount).isEqualTo(1)
    }

    @Test
    fun `een upload gebruikt de rsin en het documenttype van de document-API en de auteur van een kvk-vertegenwoordiger`() {
        bosrandOpenZaak.enqueue(json("/data/post-enkelvoudiginformatieobject-response.json"))
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        alsTenant(bosrand, JwtBuilder().vertegenwoordigdeKvk("12345678").buildAuthentication()) {
            service.uploadDocument("inhoud".toByteArray(), "bestand.txt", "openzaak")
        }

        val verzoek = bosrandOpenZaak.takeRequest()
        val body = verzoek.body.readUtf8()
        assertThat(verzoek.method).isEqualTo("POST")
        assertThat(verzoek.path).isEqualTo("/documenten/api/v1/enkelvoudiginformatieobjecten")
        assertThat(body)
            .contains("\"auteur\":\"12345678\"")
            .contains("\"bronorganisatie\":\"051845623\"")
            .contains("\"informatieobjecttype\":\"https://catalogi.example/type\"")
            .contains("\"inhoud\":\"aW5ob3Vk\"")
    }

    @Test
    fun `een upload zonder ingelogde gebruiker heeft valtimo als auteur en een eigen documenttype wint van de configuratie`() {
        bosrandOpenZaak.enqueue(json("/data/post-enkelvoudiginformatieobject-response.json"))
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        alsTenant(bosrand) { service.uploadDocument("inhoud".toByteArray(), "bestand.txt", "openzaak", "https://catalogi.example/ander") }

        val body = bosrandOpenZaak.takeRequest().body.readUtf8()
        assertThat(body).contains("\"auteur\":\"valtimo\"").contains("\"informatieobjecttype\":\"https://catalogi.example/ander\"")
    }

    @Test
    fun `een upload naar een document-API zonder rsin of documenttype weigert duidelijk en verstuurt niets`() {
        val zonder = tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)) { it.copy(documentApis = mapOf("openzaak" to DocumentApiConfiguratie())) }
        val zonderType = tenant(wijdeVeld, mapOf("openzaak" to wijdeVeldOpenZaak)) { it.copy(documentApis = mapOf("openzaak" to DocumentApiConfiguratie(rsin = "051845623"))) }
        val service = service(zonder, zonderType)

        assertThatThrownBy { alsTenant(bosrand) { service.uploadDocument("x".toByteArray(), "b.txt", "openzaak") } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThatThrownBy { alsTenant(wijdeVeld) { service.uploadDocument("x".toByteArray(), "b.txt", "openzaak") } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThat(bosrandOpenZaak.requestCount + wijdeVeldOpenZaak.requestCount).isZero()
    }

    @Test
    fun `een upload naar een onbekende document-API weigert duidelijk`() {
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)))

        assertThatThrownBy { alsTenant(bosrand) { service.uploadDocument("x".toByteArray(), "b.txt", "bestaat-niet") } }.isInstanceOf(ModuleNotConfiguredException::class.java)
    }

    @Test
    fun `een upload van een niet toegestaan mimetype wordt geweigerd voor er iets verstuurd is`() {
        bosrandOpenZaak.enqueue(json("/data/post-enkelvoudiginformatieobject-response.json"))
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)) { it.copy(toegestaneMimeTypes = setOf("text/plain")) })
        val png = DocumentenApiServiceTest::class.java.getResourceAsStream("/logo.png")!!.readBytes()

        assertThatThrownBy { alsTenant(bosrand) { service.uploadDocument(png, "logo.png", "openzaak") } }.isInstanceOf(MimeTypeDeniedException::class.java)
        assertThat(bosrandOpenZaak.requestCount).isZero()

        alsTenant(bosrand) { service.uploadDocument("gewone tekst".toByteArray(), "tekst.txt", "openzaak") }
        assertThat(bosrandOpenZaak.requestCount).isEqualTo(1)
    }

    @Test
    fun `de standaard document-API is die van de huidige tenant`() {
        val service =
            service(
                tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak, "archief" to bosrandArchief), standaard = "archief"),
                tenant(wijdeVeld, mapOf("openzaak" to wijdeVeldOpenZaak)),
            )

        assertThat(alsTenant(bosrand) { service.standaardDocumentApi() }).isEqualTo("archief")
        assertThat(alsTenant(wijdeVeld) { service.standaardDocumentApi() }).isEqualTo("openzaak")
    }

    // Filteren op de witlijsten van de tenant

    private fun document(
        status: DocumentStatus,
        vertrouwelijkheid: Vertrouwelijkheid,
    ) = Document("https://x.example/$status", "id", "2020-04-17", "titel", "text/plain", "b.txt", 1, status, vertrouwelijkheid)

    @Test
    fun `documenten worden gefilterd op de witlijsten van de huidige tenant`() {
        val streng =
            tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)) {
                it.copy(vertrouwelijkheidsaanduidingWitlijst = listOf(Vertrouwelijkheid.OPENBAAR), statusWitlijst = listOf(DocumentStatus.DEFINITIEF))
            }
        val standaard = tenant(wijdeVeld, mapOf("openzaak" to wijdeVeldOpenZaak))
        val service = service(streng, standaard)
        val documenten =
            listOf(
                document(DocumentStatus.DEFINITIEF, Vertrouwelijkheid.OPENBAAR),
                document(DocumentStatus.DEFINITIEF, Vertrouwelijkheid.INTERN),
                document(DocumentStatus.IN_BEWERKING, Vertrouwelijkheid.OPENBAAR),
                document(DocumentStatus.GEARCHIVEERD, Vertrouwelijkheid.INTERN),
                document(DocumentStatus.DEFINITIEF, Vertrouwelijkheid.VERTROUWELIJK),
            )

        val voorBosrand = alsTenant(bosrand) { service.filterDocuments(documenten) }
        val voorWijdeVeld = alsTenant(wijdeVeld) { service.filterDocuments(documenten) }

        assertThat(voorBosrand).containsExactly(documenten[0])
        assertThat(voorWijdeVeld).containsExactly(documenten[0], documenten[1], documenten[3])
    }

    @Test
    fun `zonder tenant of met een tenant zonder documenten-api weigert elke aanroep`() {
        val zonderModule = TestTenants.tenant(oin = wijdeVeld)
        val service = service(tenant(bosrand, mapOf("openzaak" to bosrandOpenZaak)), zonderModule)

        assertThatThrownBy { runBlocking { service.filterDocuments(emptyList()) } }.isInstanceOf(TenantContextMissingException::class.java)
        assertThatThrownBy { alsTenant(wijdeVeld) { service.filterDocuments(emptyList()) } }.isInstanceOf(ModuleNotConfiguredException::class.java)
    }
}