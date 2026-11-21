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
package nl.nlportal.core.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/** Seam: de actuator-configuratie zoals de runtime die leest; elke afwijking weigert het opstarten. */
class ManagementExposureGuardTest {
    private fun omgeving(vararg waarden: Pair<String, String>) = MockEnvironment().also { env -> waarden.forEach { env.setProperty(it.first, it.second) } }

    private fun fouten(vararg waarden: Pair<String, String>) = ManagementExposureGuard(omgeving(*waarden)).controleer()

    @Test
    fun `een eigen managementpoort met health en modulestatus is goed`() {
        assertThat(
            fouten("server.port" to "8080", "management.server.port" to "8091", "management.endpoints.web.exposure.include" to "health, modulestatus"),
        ).isEmpty()
    }

    @Test
    fun `zonder managementpoort weigert het opstarten`() {
        assertThat(fouten("server.port" to "8080"))
            .hasSize(1)
            .first()
            .asString()
            .contains("management.server.port is niet ingesteld")
    }

    @Test
    fun `een managementpoort gelijk aan de applicatiepoort weigert het opstarten`() {
        assertThat(fouten("server.port" to "8080", "management.server.port" to "8080")).hasSize(1)
    }

    @Test
    fun `willekeurige poorten in tests (0) zijn toegestaan`() {
        assertThat(fouten("server.port" to "0", "management.server.port" to "0")).isEmpty()
    }

    @Test
    fun `refresh, restart en een wildcard in de exposure weigeren het opstarten`() {
        for (exposure in listOf("health,refresh", "RESTART", "*", "health, Refresh ,modulestatus")) {
            assertThat(fouten("management.server.port" to "8091", "management.endpoints.web.exposure.include" to exposure))
                .describedAs(exposure)
                .hasSize(1)
        }
    }

    @Test
    fun `het opstarten faalt met alle fouten in de melding`() {
        assertThatThrownBy { ManagementExposureGuard(omgeving("management.endpoints.web.exposure.include" to "refresh")).afterPropertiesSet() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("management.server.port")
            .hasMessageContaining("refresh")
    }
}