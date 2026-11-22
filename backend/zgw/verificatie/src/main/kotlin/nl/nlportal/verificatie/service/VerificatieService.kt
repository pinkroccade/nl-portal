/*
 * Copyright 2015-2025 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.verificatie.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.ZonedDateTime
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.post
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.verificatie.client.domain.VerificatieCreateApiRequest
import nl.nlportal.verificatie.client.domain.VerificatieCreateApiResponse
import nl.nlportal.verificatie.client.domain.VerificatieVerifyApiRequest
import nl.nlportal.verificatie.client.domain.VerificatieVerifyApiResponse
import nl.nlportal.verificatie.graphql.domain.VerificatieCreateInput
import nl.nlportal.verificatie.graphql.domain.VerificatieCreateResponse
import nl.nlportal.verificatie.graphql.domain.VerificatieType
import nl.nlportal.verificatie.graphql.domain.VerificatieVerifyInput
import nl.nlportal.verificatie.graphql.domain.VerificatieVerifyResponse
import nl.nlportal.verificatie.tenant.VerificatieConfiguratie
import nl.nlportal.verificatie.tenant.VerificatieTenantConfiguratie

/**
 * Verstuurt en controleert verificatiecodes via de koppeling `verificatie` en `modules.verificatie` van de huidige tenant. De tenant volgt
 * uit de context van het request; zonder tenant of zonder die koppeling of configuratie faalt elke operatie gesloten. Alleen een fout van
 * de bron zelf geeft een mislukte respons, met een vaste tekst: de bron kan het adres of nummer van de inwoner terugsturen.
 */
class VerificatieService(
    private val register: KoppelingRegister,
    private val directory: TenantDirectory,
    private val geheimen: GeheimenBron,
) {
    /** Of een digitaal adres van dit [type] bij de huidige tenant eerst geverifieerd moet worden. */
    suspend fun vereistVerificatie(type: VerificatieType?): Boolean = configuratie().typenMetVerificatie.contains(type)

    suspend fun createVerificatie(verificatieCreateInput: VerificatieCreateInput): VerificatieCreateResponse {
        val configuratie = configuratie()
        val type = verificatieCreateInput.type
        val request =
            VerificatieCreateApiRequest(
                phoneNumber = verificatieCreateInput.waarde.takeIf { type == VerificatieType.TELEFOONNUMMER },
                email = verificatieCreateInput.waarde.takeIf { type == VerificatieType.EMAIL },
                reference = verificatieCreateInput.uuid?.toString(),
                apiKey = configuratie.apiKey?.let { geheimen.vereis(it) },
                templateId = if (type == VerificatieType.TELEFOONNUMMER) configuratie.templateIdTelefoonnummer else configuratie.templateIdEmail,
            )
        return try {
            val response: VerificatieCreateApiResponse = register.current(KOPPELING).post("/api/verification-requests", request)
            VerificatieCreateResponse(uuid = verificatieCreateInput.uuid, success = response.success)
        } catch (ex: KoppelingException) {
            logFout("aanmaken", ex)
            VerificatieCreateResponse(uuid = verificatieCreateInput.uuid, success = false, errorMessage = FOUTMELDING_AANMAKEN)
        }
    }

    suspend fun verify(verificatieVerifyInput: VerificatieVerifyInput): VerificatieVerifyResponse {
        configuratie()
        val type = verificatieVerifyInput.type
        val request =
            VerificatieVerifyApiRequest(
                phoneNumber = verificatieVerifyInput.waarde.takeIf { type == VerificatieType.TELEFOONNUMMER },
                email = verificatieVerifyInput.waarde.takeIf { type == VerificatieType.EMAIL },
                reference = verificatieVerifyInput.uuid?.toString(),
                code = verificatieVerifyInput.code,
            )
        return try {
            val response: VerificatieVerifyApiResponse = register.current(KOPPELING).post("/api/verification-requests/verify", request)
            VerificatieVerifyResponse(uuid = verificatieVerifyInput.uuid, verified = response.verified, verifiedOn = response.verifiedOn)
        } catch (ex: KoppelingException) {
            logFout("controleren", ex)
            VerificatieVerifyResponse(
                uuid = verificatieVerifyInput.uuid,
                verified = false,
                verifiedOn = ZonedDateTime.now(),
                errorMessage = FOUTMELDING_CONTROLEREN,
            )
        }
    }

    private suspend fun configuratie(): VerificatieConfiguratie = directory.module<VerificatieConfiguratie>()

    // Geen bericht, oorzaak of stacktrace: die kunnen het adres of nummer van de inwoner bevatten. De referentie vindt de aanroep terug.
    private fun logFout(
        onderdeel: String,
        ex: KoppelingException,
    ) {
        logger.error { "Verificatie $onderdeel mislukt: koppeling '${ex.koppeling}', soort ${ex.soort}, referentie ${ex.correlatieId}" }
    }

    companion object {
        const val KOPPELING = VerificatieTenantConfiguratie.KOPPELING
        const val FOUTMELDING_AANMAKEN = "Het aanmaken van het verificatieverzoek is mislukt"
        const val FOUTMELDING_CONTROLEREN = "Het controleren van de verificatiecode is mislukt"
        private val logger = KotlinLogging.logger {}
    }
}