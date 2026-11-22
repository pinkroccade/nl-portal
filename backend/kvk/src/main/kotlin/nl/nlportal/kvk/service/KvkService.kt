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
package nl.nlportal.kvk.service

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.get
import nl.nlportal.kvk.domain.MaatschappelijkeActiviteit
import nl.nlportal.kvk.domain.Vestiging
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/**
 * Bevraagt het Handelsregister via de koppeling `kvk` van de huidige tenant. De tenant volgt uit de context van het request;
 * zonder tenant of zonder die koppeling faalt een aanroep gesloten. Een nummer dat niet de vorm van een KvK- (8 cijfers) of vestigingsnummer
 * (12 cijfers) heeft gaat niet naar de bron en geeft `null`. Alleen een fout van de bron zelf geeft verder `null`.
 */
class KvkService(
    private val register: KoppelingRegister,
) {
    suspend fun getMaatschappelijkeActiviteit(authentication: PortalAuthentication): MaatschappelijkeActiviteit? {
        val vertegenwoordigde = authentication.identiteit.vertegenwoordigde
        if (vertegenwoordigde is Vertegenwoordigde.Bedrijf) {
            return getKvkData(
                kvkNummer = vertegenwoordigde.kvkNummer,
                vestigingsNummer = vertegenwoordigde.vestigingsnummer,
            )
        }

        return null
    }

    suspend fun getKvkData(
        kvkNummer: String,
        vestigingsNummer: String? = null,
    ): MaatschappelijkeActiviteit? {
        if (!KVK_NUMMER.matches(kvkNummer)) {
            logOngeldig("KvK-nummer")
            return null
        }
        val kvkData =
            try {
                register.current(KOPPELING).get<MaatschappelijkeActiviteit>("/basisprofielen/${segment(kvkNummer)}")
            } catch (ex: KoppelingException) {
                logFout("bedrijfsgegevens", ex)
                return null
            }

        // hoofdvestiging is de vestiging, tenzij het vestigingsnummer van de inlog een andere oplevert
        kvkData.embedded?.vestiging = kvkData.embedded.hoofdvestiging

        if (!vestigingsNummer.isNullOrBlank()) {
            getVestigingsData(vestigingsNummer)?.let { kvkData.embedded?.vestiging = it }
        }
        return kvkData
    }

    suspend fun getVestigingsData(vestigingsNummer: String): Vestiging? {
        if (!VESTIGINGSNUMMER.matches(vestigingsNummer)) {
            logOngeldig("vestigingsnummer")
            return null
        }
        return try {
            register.current(KOPPELING).get<Vestiging>("/vestigingsprofielen/${segment(vestigingsNummer)}")
        } catch (ex: KoppelingException) {
            logFout("vestiging", ex)
            null
        }
    }

    private fun segment(waarde: String) = UriUtils.encodePathSegment(waarde, StandardCharsets.UTF_8)

    // Het nummer zelf komt niet in de log: het hoort bij een bedrijf.
    private fun logOngeldig(soort: String) {
        logger.warn { "KvK-aanroep geweigerd: het $soort heeft een ongeldige vorm, er is niets naar de bron gestuurd" }
    }

    // Geen bericht, oorzaak of stacktrace: die kunnen het KvK-nummer bevatten. De referentie vindt de aanroep terug.
    private fun logFout(
        onderdeel: String,
        ex: KoppelingException,
    ) {
        logger.error { "KvK $onderdeel ophalen mislukt: koppeling '${ex.koppeling}', soort ${ex.soort}, referentie ${ex.correlatieId}" }
    }

    companion object {
        const val MODULE = "kvk"
        const val KOPPELING = "kvk"
        private val KVK_NUMMER = Regex("[0-9]{8}")
        private val VESTIGINGSNUMMER = Regex("[0-9]{12}")
        private val logger = KotlinLogging.logger {}
    }
}