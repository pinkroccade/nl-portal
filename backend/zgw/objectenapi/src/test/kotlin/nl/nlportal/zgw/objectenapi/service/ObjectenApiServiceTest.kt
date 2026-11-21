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
package nl.nlportal.zgw.objectenapi.service

import kotlinx.coroutines.runBlocking
import nl.nlportal.zgw.objectenapi.autoconfiguration.ObjectsApiClientConfig.ObjectsApiClientConfigProperties
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

class ObjectenApiServiceTest {
    private lateinit var bron: MockWebServer
    private lateinit var service: ObjectenApiService

    @BeforeEach
    fun start() {
        bron = MockWebServer()
        bron.start()
        service = serviceMet(bron.url("/objecten").toString().trimEnd('/'))
    }

    @AfterEach
    fun stop() {
        bron.shutdown()
    }

    private fun serviceMet(basis: String): ObjectenApiService {
        val config = ObjectsApiClientConfigProperties(URI.create(basis), "token")
        return ObjectenApiService(ObjectsApiClient(config), config)
    }

    private fun haalOp(url: String) = runBlocking { service.getObjectByUrl<Map<String, Any>>(url) }

    @Test
    fun `een object-URL onder de basis van de Objecten API wordt gevolgd met het token`() {
        bron.enqueue(MockResponse().setResponseCode(200))

        haalOp(bron.url("/objecten/api/v2/objects/1").toString())

        val verzoek = bron.takeRequest()
        assertThat(verzoek.path).isEqualTo("/objecten/api/v2/objects/1")
        assertThat(verzoek.getHeader("Authorization")).isEqualTo("Token token")
    }

    @Test
    fun `een object-URL op een andere poort van dezelfde host krijgt het token niet`() {
        val andere = MockWebServer().apply { start() }
        try {
            val fout = assertThrows<IllegalArgumentException> { haalOp(andere.url("/objecten/api/v2/objects/1").toString()) }

            assertThat(fout.message).contains("andere poort")
            assertThat(andere.requestCount).isZero()
        } finally {
            andere.shutdown()
        }
    }

    @Test
    fun `een object-URL met http tegen een https-basis krijgt het token niet`() {
        service = serviceMet("https://objecten.example.nl")

        val fout = assertThrows<IllegalArgumentException> { haalOp("http://objecten.example.nl/api/v2/objects/1") }

        assertThat(fout.message).contains("ander schema")
    }

    @Test
    fun `een object-URL buiten het pad van de basis krijgt het token niet`() {
        val fout = assertThrows<IllegalArgumentException> { haalOp(bron.url("/objecten-evil/api/v2/objects/1").toString()) }

        assertThat(fout.message).contains("buiten het pad")
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een relatieve object-URL wordt geweigerd`() {
        assertThrows<IllegalArgumentException> { haalOp("/objecten/api/v2/objects/1") }
        assertThat(bron.requestCount).isZero()
    }
}
