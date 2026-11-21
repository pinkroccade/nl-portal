/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.haalcentraalbrp.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.post
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.haalcentraalbrp.domain.bewoning.BewoningenApiRequest
import nl.nlportal.haalcentraalbrp.domain.bewoning.BewoningenApiResponse
import nl.nlportal.haalcentraalbrp.domain.brp.AanduidingNaamGebruikBrpNaam
import nl.nlportal.haalcentraalbrp.domain.brp.BrpApiRequest
import nl.nlportal.haalcentraalbrp.domain.brp.BrpApiResponse
import nl.nlportal.haalcentraalbrp.domain.brp.BrpPersoon
import nl.nlportal.haalcentraalbrp.tenant.BrpConfiguratie
import nl.nlportal.haalcentraalbrp.tenant.BrpTenantConfiguratie
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Bevraagt de BRP en de bewoningen via de koppelingen van de huidige tenant. De tenant volgt uit de context van het request; de
 * velden en de doelbinding (`modules.brp`) en de bron (`koppelingen`) zijn die van die tenant, nooit van een andere.
 */
class HaalCentraalBrpService(
    private val register: KoppelingRegister,
    private val directory: TenantDirectory,
) {
    suspend fun getPersoon(authentication: PortalAuthentication): BrpPersoon? =
        if (authentication.identiteit.vertegenwoordigde is Vertegenwoordigde.Burger) {
            val configuratie = directory.module<BrpConfiguratie>()
            val brpApiRequest =
                BrpApiRequest(
                    burgerservicenummer = listOf(authentication.identiteit.vertegenwoordigde.identificatie),
                    fields = configuratie.velden,
                )
            register
                .current(BrpTenantConfiguratie.MODULE, BrpTenantConfiguratie.PERSONEN)
                .post<BrpApiRequest, BrpApiResponse>("/personen", brpApiRequest, configuratie.aanvullendeHeaders)
                .personen
                .singleOrNull()
                ?.apply {
                    naam?.officialLastName = determineOfficialLastName(this)
                }
        } else {
            null
        }

    /**
     * De bewoningen van een adres. Zonder `modules.brp` voor de tenant faalt dit gesloten (zoals [getPersoon]); alleen een ontbrekende
     * bewoningskoppeling (optioneel, `koppelingen.brp-bewoning`) of een bronfout geeft `null`.
     */
    suspend fun getBewoningen(
        authentication: PortalAuthentication,
        adresseerbaarObjectIdentificatie: String,
    ): BewoningenApiResponse? = bewoningen(authentication, directory.module<BrpConfiguratie>(), adresseerbaarObjectIdentificatie)

    suspend fun getBewonersAantal(
        authentication: PortalAuthentication,
        adresseerbaarObjectIdentificatie: String,
        woonplaats: String? = null,
    ): Int? {
        val configuratie = directory.module<BrpConfiguratie>()
        if (allowedBewoningRequest(configuratie, woonplaats)) {
            return bewoningen(authentication, configuratie, adresseerbaarObjectIdentificatie)
                ?.bewoningen
                ?.firstOrNull()
                ?.bewoners
                ?.size
        }
        return null
    }

    private suspend fun bewoningen(
        authentication: PortalAuthentication,
        configuratie: BrpConfiguratie,
        adresseerbaarObjectIdentificatie: String,
    ): BewoningenApiResponse? {
        if (authentication.identiteit.vertegenwoordigde !is Vertegenwoordigde.Burger) {
            logger.warn { "Authentication is not supported" }
            return null
        }
        val client =
            try {
                register.current(BrpTenantConfiguratie.MODULE, BrpTenantConfiguratie.BEWONING)
            } catch (ex: ModuleNotConfiguredException) {
                // De bewoningskoppeling is optioneel: zonder die koppeling zijn er geen bewoningen (zoals vroeger zonder bewoningApiUrl).
                logger.warn { "Bewoningen ophalen kan niet: koppeling '${BrpTenantConfiguratie.BEWONING}' is voor deze tenant niet ingesteld" }
                return null
            }
        val request =
            BewoningenApiRequest(
                type = "BewoningMetPeildatum",
                peildatum = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                adresseerbaarObjectIdentificatie = adresseerbaarObjectIdentificatie,
            )
        return try {
            client.post<BewoningenApiRequest, BewoningenApiResponse>("/bewoningen", request, configuratie.aanvullendeHeaders)
        } catch (ex: KoppelingException) {
            // Geen stacktrace of oorzaak: die kunnen het BSN bevatten. De referentie vindt de aanroep terug.
            logger.error { "Bewoningen ophalen mislukt: koppeling '${ex.koppeling}', soort ${ex.soort}, referentie ${ex.correlatieId}" }
            null
        }
    }

    private fun determineOfficialLastName(persoon: BrpPersoon): String {
        if (persoon.naam == null) {
            return ""
        }
        if (persoon.naam.aanduidingNaamgebruik?.code == null) {
            return persoon.naam.lastName()
        }
        val lastNamePartner =
            persoon.partners
                ?.firstOrNull { it.naam != null }
                ?.naam
                ?.lastName()
                ?.trim() ?: ""

        return when (persoon.naam.aanduidingNaamgebruik.code) {
            AanduidingNaamGebruikBrpNaam.PARTNER.value -> {
                lastNamePartner
            }

            AanduidingNaamGebruikBrpNaam.PARTNER_EIGEN.value -> {
                "$lastNamePartner - ${persoon.naam.lastName()}"
            }

            AanduidingNaamGebruikBrpNaam.EIGEN_PARTNER.value -> {
                "${persoon.naam.lastName()} - $lastNamePartner"
            }

            else -> {
                persoon.naam.lastName()
            }
        }
    }

    private fun allowedBewoningRequest(
        configuratie: BrpConfiguratie,
        woonplaats: String? = null,
    ): Boolean {
        if (configuratie.toegestaneWoonplaatsenBewoning.isNotEmpty()) {
            if (woonplaats == null) {
                return false
            }

            if (woonplaats !in configuratie.toegestaneWoonplaatsenBewoning) {
                return false
            }
        }

        return true
    }

    companion object {
        val logger = KotlinLogging.logger {}
    }
}