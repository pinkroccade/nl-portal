/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.haalcentraalbrp.tenant

import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.TenantModuleConfiguratie

/**
 * De BRP-gegevens van een tenant onder `modules.brp` in het tenantbestand (doelbinding per gemeente). De bron zelf staat onder
 * `koppelingen.brp` (personen) en `koppelingen.brp-bewoning` (bewoningen, optioneel); zo komt er geen URL of sleutel in de module.
 */
data class BrpConfiguratie(
    /** De velden die bij de BRP worden opgevraagd (doelbinding). */
    val velden: List<String> = STANDAARD_VELDEN,
    /** Woonplaatsen waarvoor het aantal bewoners mag worden opgevraagd; leeg betekent: alle woonplaatsen. */
    val toegestaneWoonplaatsenBewoning: List<String> = emptyList(),
    /** Vaste headers voor elke aanroep naar de bron (bijvoorbeeld `x-doelbinding`); geen geheimen, die horen bij de koppeling. */
    val aanvullendeHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        val STANDAARD_VELDEN =
            listOf(
                "aNummer",
                "adressering",
                "burgerservicenummer",
                "datumEersteInschrijvingGBA",
                "datumInschrijvingInGemeente",
                "europeesKiesrecht",
                "geboorte",
                "gemeenteVanInschrijving",
                "geslacht",
                "gezag",
                "immigratie",
                "indicatieCurateleRegister",
                "kinderen",
                "leeftijd",
                "naam",
                "nationaliteiten",
                "ouders",
                "overlijden",
                "partners",
                "uitsluitingKiesrecht",
                "verblijfplaats",
                "verblijfstitel",
                "verblijfplaatsBinnenland",
                "adresseringBinnenland",
            )
    }
}

/** Registratie van [BrpConfiguratie] onder `modules.brp` (ServiceLoader, zie `META-INF/services`). */
class BrpTenantConfiguratie : TenantModuleConfiguratie<BrpConfiguratie> {
    override val naam = MODULE
    override val klasse = BrpConfiguratie::class

    override fun valideer(
        configuratie: BrpConfiguratie,
        context: ModuleContext,
    ): List<ModuleOvertreding> =
        buildList {
            if (PERSONEN !in context.koppelingen) add(ModuleOvertreding("", "koppeling '$PERSONEN' ontbreekt onder koppelingen"))
            if (configuratie.velden.isEmpty()) add(ModuleOvertreding("velden", "minstens een veld is nodig"))
            if (configuratie.velden.any { it.isBlank() }) add(ModuleOvertreding("velden", "een veld is leeg"))
            if (configuratie.toegestaneWoonplaatsenBewoning.any { it.isBlank() }) add(ModuleOvertreding("toegestaneWoonplaatsenBewoning", "een woonplaats is leeg"))
            if (configuratie.aanvullendeHeaders.keys.any { !HEADERNAAM.matches(it) }) add(ModuleOvertreding("aanvullendeHeaders", "een headernaam is ongeldig"))
            if (configuratie.aanvullendeHeaders.keys.any { it.lowercase() in VERBODEN_HEADERS }) {
                add(ModuleOvertreding("aanvullendeHeaders", "een header hoort bij de koppeling en kan hier niet worden gezet"))
            }
        }

    companion object {
        const val MODULE = "brp"
        const val PERSONEN = "brp"
        const val BEWONING = "brp-bewoning"
        private val HEADERNAAM = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private val VERBODEN_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "host", "content-length", "transfer-encoding")
    }
}