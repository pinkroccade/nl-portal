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
package nl.nlportal.berichten.configuratie

import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.NietErfbaar
import nl.nlportal.core.tenancy.TenantModuleConfiguratie

/**
 * De gegevens van Berichten voor een tenant, in het tenantbestand onder `modules.berichten`. De Objecten API waar de berichten staan is de
 * koppeling `objectenapi`, de documenten-API van de bijlagen volgt `modules.documenten-api`.
 */
data class BerichtenConfiguratie(
    /** De URL van het objecttype Bericht in de Objecten-register van deze gemeente. */
    @NietErfbaar val berichtObjectTypeUrl: String,
)

/** Draagt [BerichtenConfiguratie] bij aan het tenantbestand (T5-haakpunt). */
class BerichtenTenantConfiguratie : TenantModuleConfiguratie<BerichtenConfiguratie> {
    override val naam = NAAM
    override val klasse = BerichtenConfiguratie::class

    override fun valideer(configuratie: BerichtenConfiguratie): List<ModuleOvertreding> =
        buildList {
            if (configuratie.berichtObjectTypeUrl.isBlank()) add(ModuleOvertreding("berichtObjectTypeUrl", "mag niet leeg zijn"))
        }

    companion object {
        /** De sleutel onder `modules`. */
        const val NAAM = "berichten"
    }
}
