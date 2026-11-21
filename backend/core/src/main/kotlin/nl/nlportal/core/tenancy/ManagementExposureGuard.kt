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

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment

/**
 * Weigert het opstarten als de actuator niet op een eigen managementpoort staat of als `refresh` of `restart` bereikbaar
 * zijn. Bij een gedeelde runtime is de aparte poort een harde eis (ADR 0021 besluit 8, regel 5): anders vallen probes
 * onder de tenantresolutie en is de actuator via het publieke domein bereikbaar.
 */
class ManagementExposureGuard(
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val fouten = controleer()
        check(fouten.isEmpty()) { "Ongeldige actuator-configuratie:\n" + fouten.joinToString("\n") { " - $it" } }
    }

    fun controleer(): List<String> =
        buildList {
            val beheerpoort = environment.getProperty("management.server.port")?.toIntOrNull()
            val serverpoort = environment.getProperty("server.port")?.toIntOrNull()
            if (beheerpoort == null) {
                add("management.server.port is niet ingesteld; de actuator hoort op een eigen poort (MANAGEMENT_SERVER_PORT)")
            } else if (beheerpoort != 0 && beheerpoort == serverpoort) {
                add("management.server.port is gelijk aan server.port; de actuator mag niet op de applicatiepoort")
            }
            val exposure = environment.getProperty("management.endpoints.web.exposure.include").orEmpty()
            val bloot = exposure.split(',').map { it.trim().lowercase() }
            if ("*" in bloot) add("management.endpoints.web.exposure.include bevat '*'; noem de endpoints expliciet")
            VERBODEN.filter { it in bloot }.forEach { add("Het endpoint '$it' mag niet in de exposure: configuratie is niet live te wijzigen") }
        }

    private companion object {
        val VERBODEN = listOf("refresh", "restart")
    }
}