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
package nl.nlportal.zgw.taak.autoconfigure

import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.zgw.taak.tenant.TaakConfiguratie
import nl.nlportal.zgw.taak.tenant.TaakTenantConfiguratie
import org.springframework.beans.factory.InitializingBean

/**
 * Weigert het opstarten als de taakmodule aan staat en geen enkele tenant `modules.taak` heeft, of als een tenant `modules.taak` heeft zonder
 * de koppeling `objectenapi` waarlangs de taken lopen: elke aanroep zou dan pas bij het eerste verzoek falen. De melding noemt het OIN en
 * de sleutels in het tenantbestand, nooit een waarde.
 */
class TaakConfiguratieControle(
    private val directory: TenantDirectory,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val metTaak = directory.tenants.filter { it.module(TaakConfiguratie::class) != null }
        check(metTaak.isNotEmpty()) {
            "De taakmodule staat aan (nl-portal.config.taak.enabled), maar geen enkele tenant heeft modules.${TaakTenantConfiguratie.NAAM} " +
                "in het tenantbestand. Voeg die toe of zet de taakmodule uit."
        }
        metTaak.firstOrNull { TaakTenantConfiguratie.KOPPELING !in it.koppelingen }?.let {
            error(
                "Tenant ${it.oin.value} heeft modules.${TaakTenantConfiguratie.NAAM}, maar niet de koppeling koppelingen.${TaakTenantConfiguratie.KOPPELING} " +
                    "waarlangs de taken lopen. Voeg die toe aan het tenantbestand.",
            )
        }
    }
}