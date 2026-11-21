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
 * zonder tenant of zonder die koppeling faalt een aanroep gesloten. Alleen een fout van de bron zelf geeft `null`.
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

    suspend fun getVestigingsData(vestigingsNummer: String): Vestiging? =
        try {
            register.current(KOPPELING).get<Vestiging>("/vestigingsprofielen/${segment(vestigingsNummer)}")
        } catch (ex: KoppelingException) {
            logFout("vestiging", ex)
            null
        }

    private fun segment(waarde: String) = UriUtils.encodePathSegment(waarde, StandardCharsets.UTF_8)

    // Geen bericht, oorzaak of stacktrace: die kunnen het KvK-nummer bevatten. De referentie vindt de aanroep terug.
    private fun logFout(
        onderdeel: String,
        ex: KoppelingException,
    ) {
        logger.error { "KvK $onderdeel ophalen mislukt: koppeling '${ex.koppeling}', soort ${ex.soort}, referentie ${ex.correlatieId}" }
    }

    companion object {
        const val KOPPELING = "kvk"
        private val logger = KotlinLogging.logger {}
    }
}