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
package nl.nlportal.zgw.taak.tenant

import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.NietErfbaar
import nl.nlportal.core.tenancy.TenantModuleConfiguratie
import java.net.URI

/**
 * De taakinstellingen van een tenant, in het tenantbestand onder `modules.taak`. De verbinding met de Objecten API is de koppeling
 * `objectenapi` onder `koppelingen`; hier staat alleen welk objecttype de taken van deze gemeente bevat.
 */
data class TaakConfiguratie(
    /** De URL van het objecttype waarin de taken van deze gemeente staan. */
    @NietErfbaar val objectTypeUrl: String,
)

/** Draagt [TaakConfiguratie] bij aan het tenantbestand (T5-haakpunt). */
class TaakTenantConfiguratie : TenantModuleConfiguratie<TaakConfiguratie> {
    override val naam = NAAM
    override val klasse = TaakConfiguratie::class

    override fun valideer(configuratie: TaakConfiguratie): List<ModuleOvertreding> =
        buildList {
            val uri = runCatching { URI(configuratie.objectTypeUrl) }.getOrNull()
            if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                add(ModuleOvertreding("objectTypeUrl", "moet een absolute http(s)-URL zijn"))
            }
        }

    /** `modules.taak` zonder de koppeling `objectenapi` weigert het laden van het tenantbestand: de taken lopen via die koppeling. */
    override fun valideer(
        configuratie: TaakConfiguratie,
        context: ModuleContext,
    ): List<ModuleOvertreding> =
        valideer(configuratie) +
            if (KOPPELING in context.koppelingen) {
                emptyList()
            } else {
                listOf(ModuleOvertreding("objectTypeUrl", "vereist de koppeling '$KOPPELING' onder koppelingen"))
            }

    companion object {
        /** De koppeling waarlangs de taken lopen. */
        const val KOPPELING = "objectenapi"

        /** De sleutel onder `modules`. */
        const val NAAM = "taak"
    }
}