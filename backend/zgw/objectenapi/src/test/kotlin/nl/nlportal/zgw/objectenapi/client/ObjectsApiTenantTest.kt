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

import nl.nlportal.core.testing.TestTenants
import nl.nlportal.zgw.objectenapi.domain.Comparator
import nl.nlportal.zgw.objectenapi.domain.CreateObjectsApiObjectRequest
import nl.nlportal.zgw.objectenapi.domain.CreateObjectsApiObjectRequestRecord
import nl.nlportal.zgw.objectenapi.domain.ObjectSearchParameter
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Seam 1: de gegevens van een inwoner (BSN) gaan alleen naar de Objecten-koppeling van de eigen tenant. */
class ObjectsApiTenantTest {
    private lateinit var bosrand: MockWebServer
    private lateinit var wijdeVeld: MockWebServer
    private lateinit var client: ObjectsApiClient

    @BeforeEach
    fun start() {
        bosrand = MockWebServer().also { it.start() }
        wijdeVeld = MockWebServer().also { it.start() }
        val register =
            TestTenants.register(
                TestTenants.tenant(TestTenants.BOSRAND, koppelingen = mapOf("objectenapi" to TestTenants.koppeling(basis(bosrand), oin = TestTenants.BOSRAND))),
                TestTenants.tenant(TestTenants.WIJDE_VELD, koppelingen = mapOf("objectenapi" to TestTenants.koppeling(basis(wijdeVeld), oin = TestTenants.WIJDE_VELD))),
            )
        client = ObjectsApiClient(register)
    }

    @AfterEach
    fun stop() {
        bosrand.shutdown()
        wijdeVeld.shutdown()
    }

    private fun basis(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    private val leeg = MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("""{"count":0,"results":[]}""")

    @Test
    fun `zoeken op BSN gaat alleen naar de Objecten API van de eigen tenant`() {
        wijdeVeld.enqueue(leeg.clone())

        TestTenants.metTenant(TestTenants.WIJDE_VELD) {
            client.getObjects<Map<String, Any>>(listOf(ObjectSearchParameter("bsn", Comparator.EQUAL_TO, "999993653")), "https://types/1", 1, 10)
        }

        val verzoek = wijdeVeld.takeRequest()
        assertThat(verzoek.path).contains("/api/v2/objects").contains("999993653")
        assertThat(verzoek.getHeader("X-Api-Key")).isNotNull()
        assertThat(verzoek.getHeader("Accept-Crs")).isEqualTo("EPSG:4326")
        assertThat(bosrand.requestCount).isZero()
    }

    @Test
    fun `een nieuw object met BSN in de body gaat alleen naar de eigen tenant`() {
        bosrand.enqueue(
            MockResponse().setResponseCode(201).addHeader("Content-Type", "application/json").setBody(
                """{"uuid":"${UUID.randomUUID()}","type":"t","record":{"index":1,"typeVersion":1,"data":{"bsn":"999993653"},"startAt":"2026-01-01"}}""",
            ),
        )

        TestTenants.metTenant(TestTenants.BOSRAND) {
            client.createObject(CreateObjectsApiObjectRequest(UUID.randomUUID(), "t", CreateObjectsApiObjectRequestRecord(1, mapOf("bsn" to "999993653"), "2026-01-01")))
        }

        val verzoek = bosrand.takeRequest()
        assertThat(verzoek.body.readUtf8()).contains("999993653")
        assertThat(verzoek.getHeader("Content-Crs")).isEqualTo("EPSG:4326")
        assertThat(wijdeVeld.requestCount).isZero()
    }

    @Test
    fun `een object-URL van de andere tenant krijgt het token niet`() {
        val fout =
            runCatching {
                TestTenants.metTenant(TestTenants.BOSRAND) { client.getObjectByUrl<Map<String, Any>>(wijdeVeld.url("/api/v2/objects/1").toString()) }
            }.exceptionOrNull()

        assertThat(fout).isInstanceOf(nl.nlportal.core.koppeling.KoppelingException::class.java)
        assertThat(wijdeVeld.requestCount).isZero()
    }
}