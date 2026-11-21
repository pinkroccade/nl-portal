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
package nl.nlportal.commonground.authentication

import nl.nlportal.commonground.authentication.exception.OngeldigTokenException
import nl.nlportal.commonground.authentication.exception.UserTypeUnsupportedException
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.Identiteit
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/**
 * Bouwt de [Identiteit] uit de claims van een token. Dit is de enige plek die claims leest; de rest van de
 * code gebruikt de identiteit.
 *
 * Alleen de claim `aanvrager` (de wire-naam van de vertegenwoordigde, tot de realm-mappers `LegalSubjectID`
 * zetten) bepaalt wie er wordt vertegenwoordigd. Een token zonder BSN of KvK-nummer wordt geweigerd; er is
 * geen terugval op het originele token of op de gebruiker zelf.
 */
object IdentiteitUitToken {
    fun lees(jwt: Jwt): Identiteit {
        val vertegenwoordigde = vertegenwoordigde(jwt) ?: throw UserTypeUnsupportedException("User type not supported")
        val issuer = jwt.issuer?.toString()
        val subject = jwt.subject
        if (issuer == null || subject == null) {
            throw OngeldigTokenException("Token has no issuer or subject")
        }
        return Identiteit(
            gebruiker = Gebruiker(issuer = issuer, subject = subject),
            vertegenwoordigde = vertegenwoordigde,
            machtigingsdienstUuids = machtigingsdienstUuids(jwt),
        )
    }

    private fun vertegenwoordigde(jwt: Jwt): Vertegenwoordigde? {
        val claim = jwt.claims[VERTEGENWOORDIGDE_CLAIM] as? Map<*, *> ?: return null
        val bsn = nummer(claim, BSN_CLAIM, MAX_BSN)
        val kvk = nummer(claim, KVK_CLAIM, MAX_KVK)
        return when {
            bsn != null && kvk != null -> throw OngeldigTokenException("Token has both a BSN and a KvK number")
            bsn != null -> Vertegenwoordigde.Burger(bsn)
            kvk != null -> Vertegenwoordigde.Bedrijf(kvk, nummer(claim, VESTIGINGSNUMMER_CLAIM, MAX_VESTIGING))
            else -> null
        }
    }

    /**
     * Het nummer onder [naam], of `null` als de claim ontbreekt. Een waarde die geen niet-lege string van alleen
     * cijfers is (en niet langer dan [maxLengte]) geldt als ongeldig token: een leeg of vreemd nummer mag nooit als
     * filter bij een bron terechtkomen.
     */
    private fun nummer(
        claim: Map<*, *>,
        naam: String,
        maxLengte: Int,
    ): String? {
        val waarde = claim[naam] ?: return null
        if (waarde !is String || waarde.isEmpty() || waarde.length > maxLengte || !waarde.all { it in '0'..'9' }) {
            throw OngeldigTokenException("Token has an invalid $naam claim")
        }
        return waarde
    }

    private fun machtigingsdienstUuids(jwt: Jwt): List<UUID>? =
        try {
            when (val claim = jwt.claims[MACHTIGINGSDIENST_CLAIM]) {
                is List<*> -> claim.filterIsInstance<String>().map { UUID.fromString(it) }
                is String -> listOf(UUID.fromString(claim))
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            throw OngeldigTokenException("Token has an invalid $MACHTIGINGSDIENST_CLAIM claim")
        }

    private const val MAX_BSN = 9
    private const val MAX_KVK = 8
    private const val MAX_VESTIGING = 12

    const val VERTEGENWOORDIGDE_CLAIM = "aanvrager"
    const val BSN_CLAIM = "bsn"
    const val KVK_CLAIM = "kvk"
    const val VESTIGINGSNUMMER_CLAIM = "vestigingsnummer"
    const val MACHTIGINGSDIENST_CLAIM = "urn:etoegang:core:ServiceUUID"
}