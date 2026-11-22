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
package nl.nlportal.berichten.web.rest

import nl.nlportal.berichten.service.BerichtenService
import nl.nlportal.core.koppeling.KoppelingConfiguratieException
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.koppeling.KoppelingProblem
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.util.ModuleNotConfiguredException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

class BerichtDocumentResourceFoutenTest {
    private val service: BerichtenService = mock()
    private val gate: ModuleGate = mock()
    private val resource = BerichtDocumentResource(service, gate)

    @Test
    fun `een storing van de koppeling is een 502 met KOPPELING_FOUT en het correlatieId`() {
        val fout = KoppelingException("objectenapi", KoppelingFoutsoort.NETWERK, KoppelingProblem(title = "netwerk", status = 502))

        val response = resource.koppelingFout(fout)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        assertThat(response.body).isEqualTo(mapOf("code" to "KOPPELING_FOUT", "correlatieId" to fout.correlatieId))
    }

    @Test
    fun `een tenant zonder module is een 500 met een vaste code en een correlatieId zonder configuratiepad`() {
        assertInterneFout(resource.moduleNietGeconfigureerd(ModuleNotConfiguredException("berichten", "geheim-pad")), "MODULE_NIET_GECONFIGUREERD")
    }

    @Test
    fun `zonder tenant is het een 500 met de code TENANT_ONTBREEKT`() {
        assertInterneFout(resource.tenantOntbreekt(TenantContextMissingException()), "TENANT_ONTBREEKT")
    }

    @Test
    fun `een configuratiefout van de koppeling is een 500 met KOPPELING_FOUT zonder de melding`() {
        assertInterneFout(resource.koppelingConfiguratieFout(KoppelingConfiguratieException("geheim-pad")), "KOPPELING_FOUT")
    }

    private fun assertInterneFout(
        response: ResponseEntity<Map<String, String>>,
        code: String,
    ) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val body = response.body!!
        assertThat(body["code"]).isEqualTo(code)
        assertThat(body["correlatieId"]!!).isNotBlank()
        assertThat(body.toString()).doesNotContain("geheim-pad")
    }
}
