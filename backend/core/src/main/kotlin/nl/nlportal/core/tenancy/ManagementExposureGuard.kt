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
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
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
            val binder = Binder.get(environment)
            val beheerpoort = binder.bind("management.server.port", Int::class.javaObjectType).orElse(null)
            val serverpoort = binder.bind("server.port", Int::class.javaObjectType).orElse(STANDAARD_SERVERPOORT)
            if (beheerpoort == null) {
                add("management.server.port is niet ingesteld; de actuator hoort op een eigen poort (MANAGEMENT_SERVER_PORT)")
            } else if (beheerpoort != 0 && beheerpoort == serverpoort) {
                add("management.server.port is gelijk aan server.port; de actuator mag niet op de applicatiepoort")
            }
            val exposure: List<String> = binder.bind("management.endpoints.web.exposure.include", Bindable.listOf(String::class.java)).orElse(null).orEmpty()
            val bloot =
                exposure
                    .map { it.trim().lowercase() }
            if ("*" in bloot) add("management.endpoints.web.exposure.include bevat '*'; noem de endpoints expliciet")
            VERBODEN.filter { it in bloot }.forEach { add("Het endpoint '$it' mag niet in de exposure: configuratie is niet live te wijzigen") }
        }

    private companion object {
        const val STANDAARD_SERVERPOORT = 8080
        val VERBODEN = listOf("refresh", "restart")
    }
}