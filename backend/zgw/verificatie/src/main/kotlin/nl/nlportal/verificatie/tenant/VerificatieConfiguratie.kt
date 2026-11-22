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
package nl.nlportal.verificatie.tenant

import nl.nlportal.core.tenancy.GeheimVerwijzing
import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.NietErfbaar
import nl.nlportal.core.tenancy.TenantModuleConfiguratie
import nl.nlportal.verificatie.graphql.domain.VerificatieType

/**
 * De verificatiegegevens van een tenant onder `modules.verificatie` in het tenantbestand. De bron zelf (url en ZGW-JWT) staat onder
 * `koppelingen.verificatie`. Alles is van de gemeente en komt dus niet uit de gedeelde basis.
 */
data class VerificatieConfiguratie(
    /** De API-sleutel die in het verzoek naar de verificatiedienst meegaat; een verwijzing naar een geheim in de naamruimte van de OIN. */
    val apiKey: GeheimVerwijzing? = null,
    /** Template voor de verificatiecode per sms. */
    @NietErfbaar val templateIdTelefoonnummer: String? = null,
    /** Template voor de verificatiecode per e-mail. */
    @NietErfbaar val templateIdEmail: String? = null,
    /** De typen digitaal adres die eerst geverifieerd moeten worden. */
    @NietErfbaar val typenMetVerificatie: List<VerificatieType> = emptyList(),
)

/** Registratie van [VerificatieConfiguratie] onder `modules.verificatie` (ServiceLoader, zie `META-INF/services`). */
class VerificatieTenantConfiguratie : TenantModuleConfiguratie<VerificatieConfiguratie> {
    override val naam = MODULE
    override val klasse = VerificatieConfiguratie::class

    override fun valideer(
        configuratie: VerificatieConfiguratie,
        context: ModuleContext,
    ): List<ModuleOvertreding> =
        buildList {
            if (KOPPELING !in context.koppelingen) add(ModuleOvertreding("", "koppeling '$KOPPELING' ontbreekt onder koppelingen"))
            if (configuratie.templateIdEmail?.isBlank() == true) add(ModuleOvertreding("templateIdEmail", "het template is leeg"))
            if (configuratie.templateIdTelefoonnummer?.isBlank() == true) add(ModuleOvertreding("templateIdTelefoonnummer", "het template is leeg"))
        }

    companion object {
        const val MODULE = "verificatie"
        const val KOPPELING = "verificatie"
    }
}
