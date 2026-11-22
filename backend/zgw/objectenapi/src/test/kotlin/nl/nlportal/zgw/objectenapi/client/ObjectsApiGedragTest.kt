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
package nl.nlportal.zgw.objectenapi.client

import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.zgw.objectenapi.domain.CreateObjectsApiObjectRequest
import nl.nlportal.zgw.objectenapi.domain.CreateObjectsApiObjectRequestRecord
import nl.nlportal.zgw.objectenapi.domain.UpdateObjectsApiObjectRequest
import nl.nlportal.zgw.objectenapi.domain.UpdateObjectsApiObjectRequestRecord
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Seam 1: de gedragskeuzes van de Objecten-client en -service, met de echte koppelingsconfiguratie (`Authorization: Token ...`). */
class ObjectsApiGedragTest {
    private lateinit var bron: MockWebServer
    private lateinit var client: ObjectsApiClient
    private lateinit var service: ObjectenApiService

    @BeforeEach
    fun start() {
        bron = MockWebServer().also { it.start() }
        val auth = TenantKoppelingAuth(type = TenantKoppelingAuthType.API_KEY, headerNaam = "Authorization", waarde = TestTenants.geheim("objecten-token"))
        val register =
            TestTenants.register(
                TestTenants.tenant(TestTenants.BOSRAND, koppelingen = mapOf("objectenapi" to TestTenants.koppeling(bron.url("/").toString().trimEnd('/'), auth = auth))),
                geheimen = TestTenants.geheimen(TestTenants.geheim("objecten-token") to "Token geheim-token-123"),
            )
        client = ObjectsApiClient(register)
        service = ObjectenApiService(client)
    }

    @AfterEach
    fun stop() {
        bron.shutdown()
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse().setResponseCode(code).addHeader("Content-Type", "application/json").setBody(body)

    private val objectJson = """{"uuid":"${UUID.randomUUID()}","type":"t","record":{"index":1,"typeVersion":1,"data":{"a":"b"},"startAt":"2026-01-01"}}"""

    private fun <T> alsBosrand(blok: suspend () -> T): T = TestTenants.metTenant(TestTenants.BOSRAND, blok)

    @Test
    fun `de koppeling stuurt Authorization met het token zoals in de configuratie staat`() {
        bron.enqueue(json(200, objectJson))

        alsBosrand { client.getObjectById<Map<String, Any>>(UUID.randomUUID().toString()) }

        assertThat(bron.takeRequest().getHeader("Authorization")).isEqualTo("Token geheim-token-123")
    }

    @Test
    fun `een leeg 200-antwoord is onbruikbaar`() {
        bron.enqueue(MockResponse().setResponseCode(200))

        val fout = runCatching { alsBosrand { client.getObjectById<Map<String, Any>>(UUID.randomUUID().toString()) } }.exceptionOrNull()

        assertThat(fout).isInstanceOf(KoppelingException::class.java)
        assertThat((fout as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR)
    }

    @Test
    fun `een object-id wordt als een padsegment gecodeerd`() {
        bron.enqueue(json(200, objectJson))

        alsBosrand { client.getObjectById<Map<String, Any>>("a b") }

        assertThat(bron.takeRequest().path).isEqualTo("/api/v2/objects/a%20b")
    }

    @Test
    fun `een object-id met een schuine streep of procentteken wordt geweigerd zonder verzoek`() {
        listOf("a/b", "a%b").forEach { id ->
            val fout = runCatching { alsBosrand { client.getObjectById<Map<String, Any>>(id) } }.exceptionOrNull()

            assertThat(fout).isInstanceOf(KoppelingException::class.java)
            assertThat((fout as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.HOST_BINDING)
        }
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een object-id met een puntsegment verlaat het objectenpad niet`() {
        bron.enqueue(json(200, objectJson))

        runCatching { alsBosrand { client.getObjectById<Map<String, Any>>("../x") } }

        assertThat(bron.requestCount).isLessThanOrEqualTo(1)
        bron.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { assertThat(it.path).startsWith("/api/v2/objects/") }
    }

    @Test
    fun `een object-URL onder de basis van de koppeling wordt opgehaald`() {
        bron.enqueue(json(200, objectJson))

        val resultaat = alsBosrand { service.getObjectByUrl<Map<String, Any>>(bron.url("/api/v2/objects/42").toString()) }

        assertThat(resultaat.record.data).containsEntry("a", "b")
        assertThat(bron.takeRequest().getHeader("Accept-Crs")).isEqualTo("EPSG:4326")
    }

    @Test
    fun `de service zoekt met type, paginering en volgorde`() {
        bron.enqueue(json(200, """{"count":1,"results":[$objectJson]}"""))

        val pagina = alsBosrand { service.getObjects<Map<String, Any>>(emptyList(), "https://types/1", 2, 5) }
        val verzoek = bron.takeRequest()

        assertThat(pagina.results).hasSize(1)
        assertThat(verzoek.path).contains("page=2").contains("pageSize=5").contains("type=")
    }

    @Test
    fun `de client zoekt met een volgorde`() {
        bron.enqueue(json(200, """{"count":0,"results":[]}"""))

        alsBosrand { client.getObjects<Map<String, Any>>(emptyList(), null, 1, 10, "-record__data__datum") }

        assertThat(bron.takeRequest().path).contains("ordering=").doesNotContain("type=")
    }

    @Test
    fun `de service haalt een object op id op`() {
        bron.enqueue(json(200, objectJson))

        assertThat(alsBosrand { service.getObjectById<Map<String, Any>>(UUID.randomUUID().toString()) }.record.data).containsKey("a")
    }

    @Test
    fun `de service maakt een object aan`() {
        bron.enqueue(json(201, objectJson))

        alsBosrand { service.createObject(CreateObjectsApiObjectRequest(UUID.randomUUID(), "t", CreateObjectsApiObjectRequestRecord(1, mapOf("a" to "b"), "2026-01-01"))) }

        assertThat(bron.takeRequest().method).isEqualTo("POST")
    }

    @Test
    fun `de service werkt een object bij`() {
        bron.enqueue(json(200, objectJson))
        val uuid = UUID.randomUUID()

        alsBosrand { service.updateObject(uuid, UpdateObjectsApiObjectRequest("t", UpdateObjectsApiObjectRequestRecord(1, mapOf("a" to "b"), "2026-01-01"))) }

        val verzoek = bron.takeRequest()
        assertThat(verzoek.method).isEqualTo("PUT")
        assertThat(verzoek.path).isEqualTo("/api/v2/objects/$uuid")
    }

    @Test
    fun `de service verwijdert een object`() {
        bron.enqueue(MockResponse().setResponseCode(204))
        val uuid = UUID.randomUUID()

        alsBosrand { service.deleteObjectById(uuid) }

        val verzoek = bron.takeRequest()
        assertThat(verzoek.method).isEqualTo("DELETE")
        assertThat(verzoek.path).isEqualTo("/api/v2/objects/$uuid")
    }
}