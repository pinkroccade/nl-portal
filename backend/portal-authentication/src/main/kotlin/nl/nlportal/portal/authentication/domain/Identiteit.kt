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
package nl.nlportal.portal.authentication.domain

import java.util.UUID

/** Wie er inlogt: de uitgever (`iss`) en het onderwerp (`sub`) van het token. */
data class Gebruiker(
    val issuer: String,
    val subject: String,
)

/** Namens wie de gebruiker optreedt: een burger of een bedrijf. */
sealed interface Vertegenwoordigde {
    /** Soort van de identificatie: `bsn` of `kvk`. Geen persoonsgegeven, dus bruikbaar voor flag-targeting. */
    val soort: String

    /** Het BSN of KvK-nummer. */
    val identificatie: String

    /** Leesbare weergave voor logging in bronnen, bijvoorbeeld `BSN:123456782`. */
    val representatie: String get() = "${soort.uppercase()}:$identificatie"

    data class Burger(
        val bsn: String,
    ) : Vertegenwoordigde {
        override val soort get() = BSN
        override val identificatie get() = bsn
    }

    data class Bedrijf(
        val kvkNummer: String,
        val vestigingsnummer: String? = null,
    ) : Vertegenwoordigde {
        override val soort get() = KVK
        override val identificatie get() = kvkNummer
    }

    companion object {
        const val BSN = "bsn"
        const val KVK = "kvk"
    }
}

/**
 * De identiteit van een ingelogde gebruiker: de gebruiker en de vertegenwoordigde.
 *
 * Alleen de authenticatiemodule bouwt een identiteit (ArchUnit-regel); alle andere modules lezen hem via
 * [PortalAuthentication.identiteit].
 */
data class Identiteit(
    val gebruiker: Gebruiker,
    val vertegenwoordigde: Vertegenwoordigde,
    /** Machtigingsdienst-UUID's uit het token, of `null` als het token er geen heeft. */
    val machtigingsdienstUuids: List<UUID>? = null,
)