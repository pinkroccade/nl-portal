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
package nl.nlportal.berichten.service

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.test.runTest
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.berichten.configuratie.BerichtenConfiguratie
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Een bericht is alleen toegankelijk voor de vertegenwoordigde aan wie het gericht is. */
class BerichtenServiceTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var service: BerichtenService

    private val ontvanger = JwtBuilder().vertegenwoordigdeBsn(BSN).buildAuthentication()
    private val ander = JwtBuilder().vertegenwoordigdeKvk("12345678").buildAuthentication()

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val tenant = TestTenants.tenant(koppelingen = mapOf("objectenapi" to TestTenants.koppeling(mockServer.url("/").toString().trimEnd('/'))), modules = mapOf("berichten" to BerichtenConfiguratie("https://objecttypes.example.nl/bericht")))
        val register = TestTenants.register(tenant)
        service =
            BerichtenService(
                ObjectenApiService(ObjectsApiClient(register)),
                mock<DocumentenApiService>(),
                TestTenants.directory(tenant),
            )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `a bericht of another vertegenwoordigde is not readable`() =
        runTest(ReactorContext(TestTenants.tenantContext())) {
            mockServer.enqueue(berichtResponse(geopend = true))

            val exception = assertThrows<ResponseStatusException> { service.getBericht(ander, BERICHT_ID) }

            assertEquals(HttpStatus.UNAUTHORIZED, exception.statusCode)
        }

    @Test
    fun `an opened bericht of the vertegenwoordigde is returned without being updated`() =
        runTest(ReactorContext(TestTenants.tenantContext())) {
            mockServer.enqueue(berichtResponse(geopend = true))

            val bericht = service.getBericht(ontvanger, BERICHT_ID)

            assertEquals(BERICHT_ID, bericht?.id)
            assertEquals(1, mockServer.requestCount)
        }

    @Test
    fun `opening a bericht records the identificatie of the vertegenwoordigde as corrector`() =
        runTest(ReactorContext(TestTenants.tenantContext())) {
            mockServer.enqueue(berichtResponse(geopend = false))
            mockServer.enqueue(berichtResponse(geopend = true))

            val bericht = service.getBericht(ontvanger, BERICHT_ID)

            assertTrue(bericht!!.geopend)
            mockServer.takeRequest()
            val update = mockServer.takeRequest()
            assertEquals("PUT", update.method)
            assertTrue(update.body.readUtf8().contains("\"correctedBy\":\"$BSN\""))
        }

    @Test
    fun `a document of a bericht of another vertegenwoordigde is not readable`() =
        runTest(ReactorContext(TestTenants.tenantContext())) {
            mockServer.enqueue(berichtResponse(geopend = true))

            val exception = assertThrows<ResponseStatusException> { service.getBerichtDocumentContent(ander, BERICHT_ID, DOCUMENT_ID) }

            assertEquals(HttpStatus.UNAUTHORIZED, exception.statusCode)
        }

    @Test
    fun `a document of an unopened bericht is forbidden`() =
        runTest(ReactorContext(TestTenants.tenantContext())) {
            mockServer.enqueue(berichtResponse(geopend = false))

            val exception = assertThrows<ResponseStatusException> { service.getBerichtDocumentContent(ontvanger, BERICHT_ID, DOCUMENT_ID) }

            assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
        }

    @Test
    fun `a document that is not an attachment of the bericht is not readable`() =
        runTest(ReactorContext(TestTenants.tenantContext())) {
            mockServer.enqueue(berichtResponse(geopend = true))

            val exception = assertThrows<ResponseStatusException> { service.getBerichtDocumentContent(ontvanger, BERICHT_ID, DOCUMENT_ID) }

            assertEquals(HttpStatus.UNAUTHORIZED, exception.statusCode)
        }

    private fun berichtResponse(geopend: Boolean) =
        MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "uuid": "$BERICHT_ID",
                  "type": "https://objecttypes.example.nl/bericht",
                  "record": {
                    "index": 1,
                    "typeVersion": 1,
                    "startAt": "2026-01-01",
                    "data": {
                      "berichtTekst": "tekst",
                      "berichtType": "notificatie",
                      "bijlages": [],
                      "einddatumHandelingstermijn": "2026-12-31T00:00:00",
                      "geopend": $geopend,
                      "handelingsperspectief": "betalen",
                      "identificatie": { "type": "bsn", "value": "$BSN" },
                      "onderwerp": "onderwerp",
                      "publicatiedatum": "2026-01-01T00:00:00"
                    }
                  }
                }
                """.trimIndent(),
            )

    private companion object {
        const val BSN = "999990755"
        val BERICHT_ID: UUID = UUID.fromString("9f8e8009-2d1c-40ae-931e-c08861ba7207")
        val DOCUMENT_ID: UUID = UUID.fromString("dd328a2e-7b8e-4809-be28-f9f894c11c34")
    }
}