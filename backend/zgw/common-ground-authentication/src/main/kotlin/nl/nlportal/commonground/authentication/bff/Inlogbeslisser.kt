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
package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.Identiteit
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import java.time.Duration
import java.time.Instant

/** Wat de callback beslist: een identiteit, of een weigering met een vaste reden. */
sealed interface Inlogbeslissing {
    data class Toegestaan(
        val identiteit: Identiteit,
    ) : Inlogbeslissing

    data class Geweigerd(
        val reden: Reden,
        val actor: Actor?,
    ) : Inlogbeslissing
}

/**
 * De controles op het ID-token bij de callback (ADR 0022, ASVS 10.5): niet alleen bij de redirect. Spring valideert al
 * handtekening, `iss`, `aud`, `exp` en `nonce`; hier volgen de eisen van NL Portal:
 *
 *  - inlogmiddel uit een claim die een IdP-mapper zet (nooit uit de hint), en het middel staat aan voor de tenant;
 *  - wallet en eHerkenning worden geweigerd (wallet tot #529; eHerkenning is nog niet gebouwd);
 *  - geen vertegenwoordiging (`legal_subject_id`) en geen KvK-identiteit zolang machtigen en eHerkenning uit staan;
 *  - betrouwbaarheidsniveau uit `acr` via de volgordetabel van het inlogmiddel, minstens het minimum van de tenant; een
 *    onbekende of lege waarde geldt als afwezig en wordt geweigerd;
 *  - het BSN uit `acting_subject_id`: bij DigiD de `NameID` met de sectorprefix `s00000000:` (precies gestript; een andere
 *    prefix is een ander soort nummer, bijvoorbeeld een pseudoniem), negen cijfers met geldige elfproef;
 *  - een verse login: `auth_time` is niet ouder dan het begin van de login (`prompt=login`), zodat Keycloak niet stil
 *    een bestaande sessie teruggeeft.
 */
class Inlogbeslisser {
    fun beslis(
        tenant: TenantDefinition,
        idToken: OidcIdToken,
        loginStart: Instant?,
    ): Inlogbeslissing {
        val issuer = idToken.issuer?.toString()
        val subject = idToken.subject
        val actor = if (issuer != null && subject != null) Actor(issuer, subject) else null

        fun weiger(reden: Reden) = Inlogbeslissing.Geweigerd(reden, actor)

        if (issuer != tenant.authenticatie.issuer) return weiger(Reden.ISSUER)
        if (idToken.claims["azp"]?.let { it != tenant.authenticatie.clientId } == true ||
            idToken.audience?.contains(tenant.authenticatie.clientId) != true
        ) {
            return weiger(Reden.CLIENT)
        }
        if (subject.isNullOrBlank()) return weiger(Reden.SUBJECT)

        val middel = (idToken.claims[CLAIM_INLOGMIDDEL] as? String)?.let { naam -> Inlogmiddel.entries.firstOrNull { it.waarde == naam } }
        if (middel == null) return weiger(Reden.INLOGMIDDEL_ONBEKEND)
        if (middel !in tenant.inlogmiddelen) return weiger(Reden.INLOGMIDDEL_UIT)
        if (middel !in ONDERSTEUND) return weiger(Reden.INLOGMIDDEL_NIET_ONDERSTEUND)

        if (idToken.claims[CLAIM_VERTEGENWOORDIGDE] != null) return weiger(Reden.VERTEGENWOORDIGING)
        if (idToken.claims[CLAIM_KVK] != null) return weiger(Reden.KVK_IDENTITEIT)

        val acr = idToken.claims[CLAIM_ACR]
        if (acr == null || (acr as? String)?.isEmpty() == true) return weiger(Reden.NIVEAU_ONTBREEKT)
        val niveau = (acr as? String)?.let { NIVEAUS.getValue(middel)[it] } ?: return weiger(Reden.NIVEAU_ONBEKEND)
        if (niveau < tenant.minimumNiveau) return weiger(Reden.NIVEAU_TE_LAAG)

        val bsn = bsn(middel, idToken.claims[CLAIM_ACTING_SUBJECT]) ?: return weiger(Reden.BSN_ONGELDIG)

        val authTime = idToken.authenticatedAt
        if (loginStart == null || authTime == null || authTime.isBefore(loginStart.minus(AUTH_TIME_MARGE))) return weiger(Reden.AUTH_TIME)

        return Inlogbeslissing.Toegestaan(
            Identiteit(
                gebruiker = Gebruiker(issuer, subject, middel, niveau),
                vertegenwoordigde = Vertegenwoordigde.Burger(bsn),
            ),
        )
    }

    private fun bsn(
        middel: Inlogmiddel,
        claim: Any?,
    ): String? {
        val waarde = claim as? String ?: return null
        val nummer =
            when (middel) {
                Inlogmiddel.TEST -> waarde
                else -> waarde.takeIf { it.startsWith(BSN_SECTORPREFIX) }?.removePrefix(BSN_SECTORPREFIX)
            } ?: return null
        return nummer.takeIf { geldigBsn(it) }
    }

    companion object {
        const val CLAIM_INLOGMIDDEL = "inlogmiddel"
        const val CLAIM_ACR = "acr"
        const val CLAIM_ACTING_SUBJECT = "acting_subject_id"
        const val CLAIM_VERTEGENWOORDIGDE = "legal_subject_id"
        const val CLAIM_KVK = "kvk"

        /** DigiD-sectorprefix van een BSN in de `NameID`; andere prefixen horen bij andere nummers (pseudoniem, onderwijsnummer). */
        const val BSN_SECTORPREFIX = "s00000000:"

        /** Keycloak mag `auth_time` iets eerder zetten dan ons begin van de login (klokverschil). */
        val AUTH_TIME_MARGE: Duration = Duration.ofSeconds(60)

        private val ONDERSTEUND = setOf(Inlogmiddel.DIGID, Inlogmiddel.TEST)

        private const val SAML = "urn:oasis:names:tc:SAML:2.0:ac:classes:"
        private const val EIDAS = "http://eidas.europa.eu/LoA/"

        /**
         * De volgordetabel per inlogmiddel: Basis < Midden < Substantieel < Hoog. DigiD stuurt zijn vier URN's als
         * `AuthnContextClassRef`, de wallet de eIDAS-URI's, het testinlogmiddel altijd `Basis`. Een waarde die er niet in
         * staat is onbekend en wordt geweigerd.
         */
        internal val NIVEAUS: Map<Inlogmiddel, Map<String, Betrouwbaarheidsniveau>> =
            mapOf(
                Inlogmiddel.DIGID to
                    mapOf(
                        "${SAML}PasswordProtectedTransport" to Betrouwbaarheidsniveau.BASIS,
                        "${SAML}MobileTwoFactorContract" to Betrouwbaarheidsniveau.MIDDEN,
                        "${SAML}Smartcard" to Betrouwbaarheidsniveau.SUBSTANTIEEL,
                        "${SAML}SmartcardPKI" to Betrouwbaarheidsniveau.HOOG,
                    ),
                Inlogmiddel.WALLET to
                    mapOf(
                        "${EIDAS}low" to Betrouwbaarheidsniveau.BASIS,
                        "${EIDAS}substantial" to Betrouwbaarheidsniveau.SUBSTANTIEEL,
                        "${EIDAS}high" to Betrouwbaarheidsniveau.HOOG,
                    ),
                Inlogmiddel.EHERKENNING to emptyMap(),
                Inlogmiddel.TEST to mapOf("Basis" to Betrouwbaarheidsniveau.BASIS),
            )

        /** Negen cijfers met de elfproef: 9x1 + 8x2 + ... + 2x8 - 1x9 is deelbaar door 11 (en niet nul). */
        internal fun geldigBsn(nummer: String): Boolean {
            if (nummer.length != 9 || !nummer.all { it in '0'..'9' }) return false
            val som = (0 until 9).sumOf { i -> (nummer[i] - '0') * (if (i == 8) -1 else 9 - i) }
            return som % 11 == 0 && nummer != "000000000"
        }
    }
}