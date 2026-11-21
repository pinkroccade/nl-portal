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
package nl.nlportal.openproduct.service

import kotlinx.coroutines.test.runTest
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.openproduct.autoconfigure.OpenProductModuleConfiguration.OpenProductConfigurationProperties.OpenProductPrefillConfigurationProperties
import nl.nlportal.openproduct.client.domain.OpenProductActie
import nl.nlportal.openproduct.client.domain.OpenProductProduct
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.zgw.objectenapi.autoconfiguration.ObjectsApiClientConfig.ObjectsApiClientConfigProperties
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.openproduct.client.domain.ResultPage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Onder welke identificatie een prefill wordt vastgelegd: het BSN van een burger, het KvK-nummer van een bedrijf. */
class OpenProductPrefillServiceTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var service: OpenProductPrefillService

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        val product = mock<OpenProductProduct>(defaultAnswer = Answers.RETURNS_DEEP_STUBS)
        val actie = OpenProductActie(uuid = UUID.randomUUID(), naam = "aanvragen", url = "https://formulier.example.nl/aanvragen")
        val openProductService = mock<OpenProductService>()
        runTest {
            whenever(openProductService.getProduct(any(), eq(PRODUCT_ID))).thenReturn(product)
            whenever(openProductService.getActies(any(), any(), any())).thenReturn(ResultPage(count = 1, results = listOf(actie)))
        }

        service =
            OpenProductPrefillService(
                ObjectsApiClient(ObjectsApiClientConfigProperties(url = mockServer.url("/").toUri(), token = "token")),
                openProductService,
                OpenProductPrefillConfigurationProperties().apply { typeUrl = "https://objecttypes.example.nl/prefill" },
            )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `a prefill for a bedrijf is stored under the KvK number`() =
        runTest {
            val identificatie = prefillIdentificatie(JwtBuilder().vertegenwoordigdeKvk("12345678").buildAuthentication())

            assertEquals("kvk", identificatie["type"].asString())
            assertEquals("12345678", identificatie["value"].asString())
        }

    @Test
    fun `a prefill for a burger is stored under the BSN`() =
        runTest {
            val identificatie = prefillIdentificatie(JwtBuilder().vertegenwoordigdeBsn("123456782").buildAuthentication())

            assertEquals("bsn", identificatie["type"].asString())
            assertEquals("123456782", identificatie["value"].asString())
        }

    private suspend fun prefillIdentificatie(authentication: PortalAuthentication): tools.jackson.databind.JsonNode {
        mockServer.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setResponseCode(201)
                .setBody(
                    """{"uuid":"$OBJECT_ID","type":"t","record":{"index":1,"typeVersion":1,"data":{"nonce":"n","identificatie":{"type":"bsn","value":"1"},"data":{}},"startAt":"2026-01-01"}}""",
                ),
        )

        val response = service.prefill(authentication, PRODUCT_ID, "aanvragen")

        assertEquals(UUID.fromString(OBJECT_ID), response.objectId)
        val body = JsonMapper().readTree(mockServer.takeRequest().body.readUtf8())
        return body["record"]["data"]["identificatie"]
    }

    private companion object {
        val PRODUCT_ID: UUID = UUID.fromString("9f8e8009-2d1c-40ae-931e-c08861ba7207")
        const val OBJECT_ID = "dd328a2e-7b8e-4809-be28-f9f894c11c34"
    }
}