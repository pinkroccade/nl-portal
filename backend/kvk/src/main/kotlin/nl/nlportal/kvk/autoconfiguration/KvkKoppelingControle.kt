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
package nl.nlportal.kvk.autoconfiguration

import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.kvk.service.KvkService
import org.springframework.beans.factory.InitializingBean

/**
 * Weigert het opstarten als de KvK-module aan staat en geen enkele tenant de koppeling `kvk` heeft: elke aanroep zou dan pas bij het
 * eerste verzoek falen. Bewuste afwijking van een getypeerde moduleconfiguratie (`modules.kvk`): KvK heeft per gemeente alleen een
 * koppeling, dus de koppeling is hier de moduleconfiguratie.
 */
class KvkKoppelingControle(
    private val directory: TenantDirectory,
) : InitializingBean {
    override fun afterPropertiesSet() {
        check(directory.tenants.any { KvkService.KOPPELING in it.koppelingen }) {
            "De KvK-module staat aan (nl-portal.config.kvk.enabled), maar geen enkele tenant heeft de koppeling '${KvkService.KOPPELING}' " +
                "in het tenantbestand (koppelingen.${KvkService.KOPPELING}). Voeg die toe of zet de KvK-module uit."
        }
    }
}