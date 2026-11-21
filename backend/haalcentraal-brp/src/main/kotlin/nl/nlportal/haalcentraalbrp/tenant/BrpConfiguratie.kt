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
package nl.nlportal.haalcentraalbrp.tenant

import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.NietErfbaar
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
    /**
     * Vaste headers voor elke aanroep naar de bron (bijvoorbeeld `x-doelbinding`, `x-origin-oin`); geen geheimen, die horen bij de koppeling.
     * Niet erfbaar: ze zijn van de gemeente (doelbinding, OIN) en mogen niet uit de gedeelde basis komen.
     */
    @NietErfbaar val aanvullendeHeaders: Map<String, String> = emptyMap(),
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
            // Eén overtreding per header, zonder de naam of waarde te noemen: een headernaam kan zelf een aanwijzing voor een geheim zijn.
            val authHeaders = KOPPELINGEN.mapNotNull { context.authHeaders[it]?.lowercase() }.toSet()
            configuratie.aanvullendeHeaders.keys.forEach { header ->
                val naam = header.lowercase()
                when {
                    !HEADERNAAM.matches(header) -> {
                        add(ModuleOvertreding("aanvullendeHeaders", "een headernaam is ongeldig"))
                    }

                    naam in VERBODEN_HEADERS || naam in authHeaders -> {
                        add(ModuleOvertreding("aanvullendeHeaders", "een header hoort bij de koppeling en kan hier niet worden gezet"))
                    }

                    GEHEIMACHTIG.any { it in naam } -> {
                        add(ModuleOvertreding("aanvullendeHeaders", "een headernaam lijkt op een geheim; een geheim hoort als geheim bij de koppeling"))
                    }
                }
            }
        }

    companion object {
        const val MODULE = "brp"
        const val PERSONEN = "brp"
        const val BEWONING = "brp-bewoning"
        private val KOPPELINGEN = listOf(PERSONEN, BEWONING)
        private val GEHEIMACHTIG = listOf("key", "token", "secret", "password", "sleutel", "wachtwoord", "authorization")
        private val HEADERNAAM = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private val VERBODEN_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "host", "content-length", "transfer-encoding")
    }
}