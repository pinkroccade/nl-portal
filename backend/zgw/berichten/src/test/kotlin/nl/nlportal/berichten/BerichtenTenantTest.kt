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
import kotlinx.coroutines.flow.emptyFlow
import nl.nlportal.documentenapi.domain.Document
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

/** Seam 1: het BSN van een inwoner en het objecttype van Berichten gaan alleen naar de Objecten-koppeling en de configuratie van de eigen tenant. */
class BerichtenTenantTest {
    private lateinit var bosrand: MockWebServer
    private lateinit var wijdeVeld: MockWebServer
    private lateinit var service: BerichtenService
    private val documenten = mock<DocumentenApiService>()

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
        service = BerichtenService(ObjectenApiService(ObjectsApiClient(TestTenants.register(*tenants))), documenten, TestTenants.directory(*tenants))
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
            assertThat(verzoek.path)
                .contains("/api/v2/objects")
                .contains(BSN)
                .contains("type=")
                .contains("wijde-veld-bericht")
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

    @Test
    fun `het openen van een bericht leest en schrijft alleen bij de eigen tenant, met het BSN in de update`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(bericht(TYPE_BOSRAND, geopend = false))
            bosrand.enqueue(bericht(TYPE_BOSRAND, geopend = true))

            val gelezen = service.getBericht(inwoner, BERICHT_ID)

            assertThat(gelezen?.geopend).isTrue()
            val lees = bosrand.takeRequest()
            assertThat(lees.method).isEqualTo("GET")
            assertThat(lees.path).contains(BERICHT_ID.toString())
            val schrijf = bosrand.takeRequest()
            assertThat(schrijf.method).isEqualTo("PUT")
            assertThat(schrijf.path).contains(BERICHT_ID.toString())
            assertThat(schrijf.body.readUtf8()).contains("\"value\":\"$BSN\"").contains("\"geopend\":true").contains("\"correctedBy\":\"$BSN\"")
            assertThat(bosrand.requestCount).isEqualTo(2)
            assertThat(wijdeVeld.requestCount).isZero()
        }

    @Test
    fun `een bericht met het objecttype van een andere tenant wordt niet gevonden en niet herschreven`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(bericht(TYPE_WIJDE_VELD, geopend = false))

            val gelezen = service.getBericht(inwoner, BERICHT_ID)

            assertThat(gelezen).isNull()
            assertThat(bosrand.requestCount).isEqualTo(1)
            assertThat(bosrand.takeRequest().method).isEqualTo("GET")
            assertThat(wijdeVeld.requestCount).isZero()
        }

    @Test
    fun `een bijlage wordt alleen gedownload voor een bericht van de eigen tenant`() {
        val document = mock<Document>()
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.WIJDE_VELD))) {
            whenever(documenten.getDocument(any())).doReturn(document)
            whenever(documenten.getDocumentContentStreaming(any())).doReturn(emptyFlow())
            wijdeVeld.enqueue(bericht(TYPE_WIJDE_VELD, geopend = true, bijlage = BIJLAGE_URL))

            val resultaat = service.getBerichtDocumentContent(inwoner, BERICHT_ID, DOCUMENT_ID)

            assertThat(resultaat?.first).isSameAs(document)
            assertThat(wijdeVeld.takeRequest().path).contains(BERICHT_ID.toString())
            assertThat(bosrand.requestCount).isZero()
            verify(documenten).getDocument(BIJLAGE_URL)
        }
    }

    @Test
    fun `een bijlage van een bericht met het objecttype van een andere tenant wordt niet gedownload`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(bericht(TYPE_WIJDE_VELD, geopend = true, bijlage = BIJLAGE_URL))

            val resultaat = service.getBerichtDocumentContent(inwoner, BERICHT_ID, DOCUMENT_ID)

            assertThat(resultaat).isNull()
            assertThat(wijdeVeld.requestCount).isZero()
            verifyNoInteractions(documenten)
        }

    @Test
    fun `een tenant zonder berichten-configuratie krijgt voor detail en download geen verzoek`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.WIJDE_VELD))) {
            val zonder = tenant(TestTenants.WIJDE_VELD, wijdeVeld, type = null)
            val zonderService =
                BerichtenService(ObjectenApiService(ObjectsApiClient(TestTenants.register(zonder))), documenten, TestTenants.directory(zonder))

            assertThrows<ModuleNotConfiguredException> { zonderService.getBericht(inwoner, BERICHT_ID) }
            assertThrows<ModuleNotConfiguredException> { zonderService.getBerichtDocumentContent(inwoner, BERICHT_ID, DOCUMENT_ID) }

            assertThat(wijdeVeld.requestCount).isZero()
            verify(documenten, never()).getDocument(any())
        }

    private fun bericht(
        type: String,
        geopend: Boolean,
        bijlage: String? = null,
    ): MockResponse {
        val bijlages = bijlage?.let { "\"$it\"" } ?: ""
        return MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(
            """
            {"uuid":"$BERICHT_ID","type":"$type","record":{"index":1,"typeVersion":1,"startAt":"2026-01-01","data":{
              "berichtTekst":"tekst","berichtType":"notificatie","bijlages":[$bijlages],
              "einddatumHandelingstermijn":"2026-12-31T00:00:00","geopend":$geopend,"handelingsperspectief":"betalen",
              "identificatie":{"type":"bsn","value":"$BSN"},"onderwerp":"onderwerp","publicatiedatum":"2026-01-01T00:00:00"}}}
            """.trimIndent(),
        )
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
        val BERICHT_ID: UUID = UUID.fromString("9f8e8009-2d1c-40ae-931e-c08861ba7207")
        val DOCUMENT_ID: UUID = UUID.fromString("dd328a2e-7b8e-4809-be28-f9f894c11c34")
        val BIJLAGE_URL = "https://documenten.example.nl/enkelvoudiginformatieobjecten/$DOCUMENT_ID"
        const val TYPE_BOSRAND = "https://objecttypes.example.nl/api/v2/objecttypes/bosrand-bericht"
        const val TYPE_WIJDE_VELD = "https://objecttypes.example.nl/api/v2/objecttypes/wijde-veld-bericht"
    }
}