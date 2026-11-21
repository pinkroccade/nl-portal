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
package nl.nlportal.payment.direct.configuratie

import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.TenantModuleConfiguratie

/** Draagt `modules.payment-direct` bij aan het tenantbestand (registratie via `META-INF/services`). */
class DirectPaymentTenantConfiguratie : TenantModuleConfiguratie<DirectPaymentConfiguratie> {
    override val naam = NAAM
    override val klasse = DirectPaymentConfiguratie::class

    override fun valideer(
        configuratie: DirectPaymentConfiguratie,
        context: ModuleContext,
    ): List<ModuleOvertreding> =
        buildList {
            if (configuratie.profielen.isEmpty()) add(ModuleOvertreding("profielen", "minstens een profiel"))
            configuratie.profielen.forEach { (naam, profiel) ->
                if (profiel.koppelingNaam() !in context.koppelingen) {
                    add(ModuleOvertreding("profielen.$naam.koppeling", "de koppeling staat niet onder koppelingen"))
                }
                if ((profiel.webhookSleutelId == null) != (profiel.webhookGeheim == null)) {
                    add(ModuleOvertreding("profielen.$naam", "webhookSleutelId en webhookGeheim horen samen"))
                }
                if (profiel.aantalPogingen < 1) add(ModuleOvertreding("profielen.$naam.aantalPogingen", "minstens 1"))
            }
            configuratie.profielen.entries
                .filter { it.value.webhookSleutelId != null }
                .groupBy { it.value.webhookSleutelId }
                .filterValues { it.size > 1 }
                .values
                .forEach { dubbel -> add(ModuleOvertreding("profielen.${dubbel.last().key}.webhookSleutelId", "hetzelfde sleutel-ID bij meer profielen")) }
        }

    companion object {
        const val NAAM = "payment-direct"
    }
}