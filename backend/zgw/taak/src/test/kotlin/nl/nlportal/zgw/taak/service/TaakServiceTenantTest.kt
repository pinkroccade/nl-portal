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
package nl.nlportal.zgw.taak.service

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.test.runTest
import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.taak.TestHelper.mockJsonResponse
import nl.nlportal.zgw.taak.TestHelper.resultPage
import nl.nlportal.zgw.taak.tenant.TaakConfiguratie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/** Seam 1: het BSN van een inwoner en de taakgegevens gaan alleen naar de Objecten-koppeling van de eigen tenant, met het objecttype van die tenant. */
class TaakServiceTenantTest {
    private lateinit var bosrand: MockWebServer
    private lateinit var wijdeVeld: MockWebServer
    private lateinit var service: TaakService

    @BeforeEach
    fun start() {
        bosrand = MockWebServer().also { it.start() }
        wijdeVeld = MockWebServer().also { it.start() }
        val bosrandTenant =
            TestTenants.tenant(
                TestTenants.BOSRAND,
                koppelingen = mapOf("objectenapi" to TestTenants.koppeling(basis(bosrand), oin = TestTenants.BOSRAND)),
                modules = mapOf("taak" to TaakConfiguratie(TYPE_BOSRAND)),
            )
        val wijdeVeldTenant =
            TestTenants.tenant(
                TestTenants.WIJDE_VELD,
                koppelingen = mapOf("objectenapi" to TestTenants.koppeling(basis(wijdeVeld), oin = TestTenants.WIJDE_VELD)),
                modules = mapOf("taak" to TaakConfiguratie(TYPE_WIJDE_VELD)),
            )
        val machtigingen = mock<AuthenticationMachtigingsDienstService>()
        whenever(machtigingen.isAllowedTaakType(any(), any())).thenReturn(true)
        service =
            TaakService(
                ObjectsApiClient(TestTenants.register(bosrandTenant, wijdeVeldTenant)),
                TestTenants.directory(bosrandTenant, wijdeVeldTenant),
                machtigingen,
            )
    }

    @AfterEach
    fun stop() {
        bosrand.shutdown()
        wijdeVeld.shutdown()
    }

    private fun basis(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    private fun burger() = JwtBuilder().vertegenwoordigdeBsn(BSN).buildAuthentication()

    @Test
    fun `taken zoeken op BSN gaat alleen naar de eigen tenant met het objecttype van die tenant`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.WIJDE_VELD))) {
            wijdeVeld.enqueue(mockJsonResponse(resultPage()))

            service.getTakenV2(1, 10, burger())

            val verzoek = wijdeVeld.takeRequest()
            assertThat(verzoek.requestUrl!!.queryParameter("type")).isEqualTo(TYPE_WIJDE_VELD)
            assertThat(verzoek.requestUrl!!.queryParameter("data_attrs")).contains("identificatie__value__exact__$BSN")
            assertThat(bosrand.requestCount).isZero()
        }

    @Test
    fun `een afgeronde taak met BSN in de body (correctedBy) gaat alleen naar de eigen tenant`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(mockJsonResponse(taakJson("open")))
            bosrand.enqueue(mockJsonResponse(taakJson("afgerond")))

            service.submitTaak(UUID.fromString(TAAK_ID), mapOf("voornaam" to "Piet"), burger())

            bosrand.takeRequest()
            val put = bosrand.takeRequest()
            assertThat(put.method).isEqualTo("PUT")
            assertThat(put.body.readUtf8()).contains("\"correctedBy\":\"BSN:$BSN\"")
            assertThat(wijdeVeld.requestCount).isZero()
        }

    @Test
    fun `zonder tenant faalt het zoeken gesloten en gaat er niets naar een bron`() {
        runTest {
            assertThrows<TenantContextMissingException> { service.getTakenV2(1, 10, burger()) }
        }
        assertThat(bosrand.requestCount + wijdeVeld.requestCount).isZero()
    }

    @Test
    fun `een tenant zonder modules taak faalt gesloten in plaats van een lege pagina`() {
        val zonder = TestTenants.tenant(TestTenants.BOSRAND, koppelingen = mapOf("objectenapi" to TestTenants.koppeling(basis(bosrand))))
        val machtigingen = mock<AuthenticationMachtigingsDienstService>()
        val zonderService = TaakService(ObjectsApiClient(TestTenants.register(zonder)), TestTenants.directory(zonder), machtigingen)

        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            assertThrows<ModuleNotConfiguredException> { zonderService.getTakenV2(1, 10, burger()) }
        }
        assertThat(bosrand.requestCount).isZero()
    }

    @Test
    fun `detail en afronden zonder modules taak falen gesloten zonder verzoek`() {
        val zonder = TestTenants.tenant(TestTenants.BOSRAND, koppelingen = mapOf("objectenapi" to TestTenants.koppeling(basis(bosrand))))
        val machtigingen = mock<AuthenticationMachtigingsDienstService>()
        val zonderService = TaakService(ObjectsApiClient(TestTenants.register(zonder)), TestTenants.directory(zonder), machtigingen)

        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            assertThrows<ModuleNotConfiguredException> { zonderService.getTaakByIdV2(UUID.fromString(TAAK_ID), burger()) }
            assertThrows<ModuleNotConfiguredException> { zonderService.submitTaakV2(UUID.fromString(TAAK_ID), mapOf("a" to "b"), burger()) }
        }
        assertThat(bosrand.requestCount).isZero()
    }

    @Test
    fun `detail van een object met het objecttype van een andere tenant geeft 404`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(mockJsonResponse(taakJson("open", TYPE_WIJDE_VELD)))

            val fout = assertThrows<ResponseStatusException> { service.getTaakByIdV2(UUID.fromString(TAAK_ID), burger()) }

            assertThat(fout.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

    @Test
    fun `een vreemd objecttype en een onbekende UUID zijn voor de browser niet te onderscheiden`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("{}"))
            val onbekend = assertThrows<ResponseStatusException> { service.getTaakByIdV2(UUID.fromString(TAAK_ID), burger()) }
            bosrand.enqueue(mockJsonResponse(taakJson("open", TYPE_WIJDE_VELD)))
            val vreemd = assertThrows<ResponseStatusException> { service.getTaakByIdV2(UUID.fromString(TAAK_ID), burger()) }

            assertThat(vreemd.statusCode).isEqualTo(onbekend.statusCode)
            assertThat(vreemd.message).isEqualTo(onbekend.message)
        }

    @Test
    fun `afronden van een object met een ander objecttype geeft 404 en schrijft niets`() =
        runTest(ReactorContext(TestTenants.tenantContext(TestTenants.BOSRAND))) {
            bosrand.enqueue(mockJsonResponse(taakJson("open", TYPE_WIJDE_VELD)))

            val fout = assertThrows<ResponseStatusException> { service.submitTaakV2(UUID.fromString(TAAK_ID), mapOf("a" to "b"), burger()) }

            assertThat(fout.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(bosrand.requestCount).isEqualTo(1)
        }

    private fun taakJson(
        status: String,
        type: String = TYPE_BOSRAND,
    ) = """
        {"uuid":"$TAAK_ID","type":"$type","record":{"index":9,"typeVersion":1,"startAt":"2022-06-01","data":{
        "titel":"t","status":"$status","soort":"portaalformulier","verloopdatum":"2023-09-20T18:25:43.524Z","eigenaar":"gzac",
        "identificatie":{"type":"bsn","value":"$BSN"},"koppeling":{"registratie":"zaak","value":"5551a7c5-4e92-43e6-8d23-80359b7e22b7"},
        "portaalformulier":{"formulier":{"soort":"url","value":"http://localhost:8010/api/v2/objects/4e40fb4c"},"data":{"voornaam":"Jan"}},
        "verwerker_taak_id":"58fad5ab-dc2f-11ec-9075-f22a405ce708"}}}
        """.trimIndent()

    companion object {
        private const val TAAK_ID = "58fad5ab-dc2f-11ec-9075-f22a405ce707"
        private const val BSN = "569312863"
        private const val TYPE_BOSRAND = "https://objecttypen.bosrand.example/api/v1/objecttypes/aaaa"
        private const val TYPE_WIJDE_VELD = "https://objecttypen.wijdeveld.example/api/v1/objecttypes/bbbb"
    }
}