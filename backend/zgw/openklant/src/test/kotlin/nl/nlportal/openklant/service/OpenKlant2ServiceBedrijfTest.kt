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
package nl.nlportal.openklant.service

import kotlinx.coroutines.test.runTest
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.openklant.TestHelper.emptyPage
import nl.nlportal.openklant.TestHelper.mockJsonResponse
import nl.nlportal.openklant.TestHelper.singlePartij
import nl.nlportal.openklant.TestHelper.url
import nl.nlportal.openklant.autoconfigure.OpenKlantModuleConfiguration
import nl.nlportal.openklant.autoconfigure.OpenKlantModuleConfiguration.OpenKlantConfigurationProperties
import nl.nlportal.openklant.client.OpenKlant2KlantinteractiesClient
import nl.nlportal.openklant.client.domain.OpenKlant2DigitaleAdres
import nl.nlportal.openklant.client.domain.OpenKlant2Partij
import nl.nlportal.openklant.client.domain.OrganisatieIdentificatie
import nl.nlportal.openklant.client.domain.SoortPartij
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Hoe [OpenKlant2Service] een bedrijf bij OpenKlant 2 opzoekt en aanmaakt: via de vestiging als het token er een heeft,
 * anders via het KvK-nummer.
 */
class OpenKlant2ServiceBedrijfTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var service: OpenKlant2Service

    private val bedrijfZonderVestiging = JwtBuilder().vertegenwoordigdeKvk(KVK).buildAuthentication()
    private val bedrijfMetVestiging = JwtBuilder().vestigingsNummerKvk(KVK, VESTIGING).buildAuthentication()
    private val burger = JwtBuilder().vertegenwoordigdeBsn(BSN).buildAuthentication()

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val properties =
            OpenKlantModuleConfiguration()
                .apply {
                    enabled = true
                    this.properties =
                        OpenKlantConfigurationProperties(
                            klantinteractiesApiUrl = mockServer.url("/myapi/v1").toUri(),
                            token = "SuperSecretToken1234",
                        )
                }.properties
        service = OpenKlant2Service(OpenKlant2KlantinteractiesClient(properties), properties)
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `a partij search for a bedrijf asks for an organisatie with the KvK number`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findPartijByAuthentication(bedrijfZonderVestiging)

            val url = mockServer.takeRequest().url
            assertEquals("organisatie", url.queryParameter("soortPartij"))
            assertEquals(KVK, url.queryParameter("partijIdentificator__objectId"))
        }

    @Test
    fun `a partij search for a burger asks for a persoon with the BSN`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findPartijByAuthentication(burger)

            val url = mockServer.takeRequest().url
            assertEquals("persoon", url.queryParameter("soortPartij"))
            assertEquals(BSN, url.queryParameter("partijIdentificator__objectId"))
        }

    @Test
    fun `partij identificatoren of a bedrijf are searched by KvK number in the HR`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findPartijIdentificatorenFromAuthentication(bedrijfZonderVestiging)

            assertEquals(
                mapOf(
                    "partijIdentificatorCodeRegister" to "hr",
                    "partijIdentificatorCodeObjecttype" to "niet_natuurlijk_persoon",
                    "partijIdentificatorCodeSoortObjectId" to "kvk_nummer",
                    "partijIdentificatorObjectId" to KVK,
                ),
                mockServer.takeRequest().url.queryStartingWith("partijIdentificator"),
            )
        }

    @Test
    fun `partij identificatoren of a bedrijf with a vestiging are searched by vestigingsnummer`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findPartijIdentificatorenFromAuthentication(bedrijfMetVestiging)

            assertEquals(
                mapOf(
                    "partijIdentificatorCodeRegister" to "hr",
                    "partijIdentificatorCodeObjecttype" to "vestiging",
                    "partijIdentificatorCodeSoortObjectId" to "vestigingsnummer",
                    "partijIdentificatorObjectId" to VESTIGING,
                ),
                mockServer.takeRequest().url.queryStartingWith("partijIdentificator"),
            )
        }

    @Test
    fun `partij identificatoren of a burger are searched by BSN in the BRP`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findPartijIdentificatorenFromAuthentication(burger)

            assertEquals(
                mapOf(
                    "partijIdentificatorCodeRegister" to "brp",
                    "partijIdentificatorCodeObjecttype" to "natuurlijk_persoon",
                    "partijIdentificatorCodeSoortObjectId" to "bsn",
                    "partijIdentificatorObjectId" to BSN,
                ),
                mockServer.takeRequest().url.queryStartingWith("partijIdentificator"),
            )
        }

    @Test
    fun `digitale adressen of a bedrijf with a vestiging are searched by vestigingsnummer`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findDigitaleAdressen(bedrijfMetVestiging)

            assertEquals(
                mapOf(
                    "verstrektDoorPartij__partijIdentificator__codeRegister" to "hr",
                    "verstrektDoorPartij__partijIdentificator__codeObjecttype" to "vestiging",
                    "verstrektDoorPartij__partijIdentificator__codeSoortObjectId" to "vestigingsnummer",
                    "verstrektDoorPartij__partijIdentificator__objectId" to VESTIGING,
                ),
                mockServer.takeRequest().url.queryStartingWith("verstrektDoorPartij__partijIdentificator"),
            )
        }

    @Test
    fun `digitale adressen of a bedrijf without vestiging are searched by KvK number`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findDigitaleAdressen(bedrijfZonderVestiging)

            val url = mockServer.takeRequest().url
            assertEquals(KVK, url.queryParameter("verstrektDoorPartij__partijIdentificator__objectId"))
            assertEquals("niet_natuurlijk_persoon", url.queryParameter("verstrektDoorPartij__partijIdentificator__codeObjecttype"))
        }

    @Test
    fun `klantcontacten of a bedrijf with a vestiging are searched by vestigingsnummer`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findKlantContacten(bedrijfMetVestiging)

            assertEquals(
                mapOf(
                    "hadBetrokkene__wasPartij__partijIdentificator__codeRegister" to "hr",
                    "hadBetrokkene__wasPartij__partijIdentificator__codeObjecttype" to "vestiging",
                    "hadBetrokkene__wasPartij__partijIdentificator__codeSoortObjectId" to "vestigingsnummer",
                    "hadBetrokkene__wasPartij__partijIdentificator__objectId" to VESTIGING,
                ),
                mockServer.takeRequest().url.queryStartingWith("hadBetrokkene__wasPartij__partijIdentificator"),
            )
        }

    @Test
    fun `klantcontacten of a burger are searched by BSN`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))

            service.findKlantContacten(burger)

            assertEquals(BSN, mockServer.takeRequest().url.queryParameter("hadBetrokkene__wasPartij__partijIdentificator__objectId"))
        }

    @Test
    fun `a partij for a bedrijf with a vestiging is linked to the existing partij identificator of the KvK number`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(page(partijIdentificatorJson(KVK_IDENTIFICATOR_ID, KVK_PARTIJ_ID))))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(partijIdentificatorJson(VESTIGING_IDENTIFICATOR_ID, PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))

            val partij = service.createPartijWithIdentificatorFromAuthentication(bedrijfMetVestiging, organisatiePartij())

            assertNotNull(partij)
            val lookup = mockServer.takeRequest()
            assertEquals(KVK, lookup.url.queryParameter("partijIdentificatorObjectId"))
            assertEquals("kvk_nummer", lookup.url.queryParameter("partijIdentificatorCodeSoortObjectId"))
            mockServer.takeRequest() // partij aanmaken
            val identificator = mockServer.takeRequest().body.readUtf8()
            assertTrue(identificator.contains("\"objectId\":\"$VESTIGING\""), identificator)
            assertTrue(identificator.contains("\"codeSoortObjectId\":\"vestigingsnummer\""), identificator)
            assertTrue(identificator.contains("\"subIdentificatorVan\":{\"uuid\":\"$KVK_IDENTIFICATOR_ID\"}"), identificator)
            assertEquals(4, mockServer.requestCount)
        }

    @Test
    fun `a partij for a bedrijf with a vestiging creates the partij of the KvK number first when it does not exist`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))
            mockServer.enqueue(mockJsonResponse(singlePartij(KVK_PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(partijIdentificatorJson(KVK_IDENTIFICATOR_ID, KVK_PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(partijMetIdentificator(KVK_PARTIJ_ID, KVK_IDENTIFICATOR_ID)))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(partijIdentificatorJson(VESTIGING_IDENTIFICATOR_ID, PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))

            val partij = service.createPartijWithIdentificatorFromAuthentication(bedrijfMetVestiging, organisatiePartij())

            assertNotNull(partij)
            val requests = generateSequence { mockServer.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
            assertEquals(7, requests.size)
            val kvkIdentificator = requests[2].body.readUtf8()
            assertTrue(kvkIdentificator.contains("\"objectId\":\"$KVK\""), kvkIdentificator)
            assertTrue(kvkIdentificator.contains("\"codeObjecttype\":\"niet_natuurlijk_persoon\""), kvkIdentificator)
            val vestigingIdentificator = requests[5].body.readUtf8()
            assertTrue(vestigingIdentificator.contains("\"subIdentificatorVan\":{\"uuid\":\"$KVK_IDENTIFICATOR_ID\"}"), vestigingIdentificator)
        }

    @Test
    fun `a digitaal adres for a bedrijf without a partij creates an organisatie partij named after the KvK number`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(partijIdentificatorJson(KVK_IDENTIFICATOR_ID, PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(digitaalAdresJson()))

            val adres = service.createDigitaleAdres(bedrijfZonderVestiging, digitaalAdres())

            assertNotNull(adres)
            mockServer.takeRequest() // zoeken naar bestaande partij
            val partijRequest = mockServer.takeRequest().body.readUtf8()
            assertTrue(partijRequest.contains("\"soortPartij\":\"organisatie\""), partijRequest)
            assertTrue(partijRequest.contains("\"naam\":\"$KVK\""), partijRequest)
            mockServer.takeRequest() // identificator
            mockServer.takeRequest() // partij ophalen
            val adresRequest = mockServer.takeRequest().body.readUtf8()
            assertTrue(adresRequest.contains("\"verstrektDoorPartij\":{\"uuid\":\"$PARTIJ_ID\"}"), adresRequest)
        }

    @Test
    fun `a digitaal adres for a burger without a partij creates a persoon partij`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(partijIdentificatorJson(KVK_IDENTIFICATOR_ID, PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(singlePartij(PARTIJ_ID)))
            mockServer.enqueue(mockJsonResponse(digitaalAdresJson()))

            assertNotNull(service.createDigitaleAdres(burger, digitaalAdres()))

            mockServer.takeRequest()
            val partijRequest = mockServer.takeRequest().body.readUtf8()
            assertTrue(partijRequest.contains("\"soortPartij\":\"persoon\""), partijRequest)
        }

    @Test
    fun `a digitaal adres is not created when the partij of the bedrijf can not be created`() =
        runTest {
            mockServer.enqueue(mockJsonResponse(emptyPage))
            mockServer.enqueue(MockResponse().setResponseCode(500))

            assertNull(service.createDigitaleAdres(bedrijfZonderVestiging, digitaalAdres()))
        }

    private fun HttpUrl.queryStartingWith(prefix: String): Map<String, String> =
        queryParameterNames
            .filter { it.startsWith(prefix) }
            .associateWith { checkNotNull(queryParameter(it)) }

    private fun organisatiePartij() =
        OpenKlant2Partij(
            indicatieActief = true,
            soortPartij = SoortPartij.ORGANISATIE,
            partijIdentificatie = OrganisatieIdentificatie(naam = KVK),
        )

    private fun digitaalAdres() = OpenKlant2DigitaleAdres(adres = "info@example.nl", omschrijving = "werk", soortDigitaalAdres = "email")

    private fun digitaalAdresJson() = """{"uuid":"$ADRES_ID","adres":"info@example.nl","omschrijving":"werk","soortDigitaalAdres":"email"}"""

    private fun page(vararg items: String) = """{"count":${items.size},"next":null,"previous":null,"results":[${items.joinToString(",")}]}"""

    private fun partijIdentificatorJson(
        uuid: String,
        partijId: String,
    ) = """{"uuid":"$uuid","identificeerdePartij":{"uuid":"$partijId"}}"""

    private fun partijMetIdentificator(
        partijId: String,
        identificatorId: String,
    ) = """{"uuid":"$partijId","soortPartij":"organisatie","indicatieActief":true,"partijIdentificatie":{"naam":"$KVK"},"partijIdentificatoren":[{"uuid":"$identificatorId"}]}"""

    companion object {
        private const val KVK = "69599084"
        private const val VESTIGING = "000037178899"
        private const val BSN = "296648875"
        private const val PARTIJ_ID = "9f8e8009-2d1c-40ae-931e-c08861ba7207"
        private const val KVK_PARTIJ_ID = "0a1b2c3d-1111-4222-8333-444455556666"
        private const val KVK_IDENTIFICATOR_ID = "dd328a2e-7b8e-4809-be28-f9f894c11c34"
        private const val VESTIGING_IDENTIFICATOR_ID = "ee439b3f-8c9f-4910-8f39-0a0a05d22d45"
        private const val ADRES_ID = "1300d2ab-13cb-4c12-9818-3b76e2a5d993"
    }
}