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

import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import java.util.UUID

/**
 * Wie er inlogt (de actor): de uitgever (`iss`) en het onderwerp (`sub`) van het ID-token, het inlogmiddel waarmee en het
 * betrouwbaarheidsniveau waarop is ingelogd. De BFF controleert beide bij de callback voor ze hier terechtkomen.
 */
data class Gebruiker(
    val issuer: String,
    val subject: String,
    val inlogmiddel: Inlogmiddel,
    val niveau: Betrouwbaarheidsniveau,
)

/** Soort van de identificatie van een vertegenwoordigde. */
enum class Soort(
    /** Waarde zoals bronnen die kennen (`identificatie__type`); geen persoonsgegeven, dus ook bruikbaar voor flag-targeting. */
    val code: String,
) {
    BSN("bsn"),
    KVK("kvk"),
}

/** Namens wie de gebruiker optreedt: een burger of een bedrijf. */
sealed interface Vertegenwoordigde {
    val soort: Soort

    /** Het BSN of KvK-nummer. */
    val identificatie: String

    /**
     * Auditveld bij de bron (`correctedBy`), bijvoorbeeld `BSN:123456782`. Bevat het nummer; gebruik het niet in
     * logregels of foutmeldingen.
     */
    val representatie: String get() = "${soort.name}:$identificatie"

    data class Burger(
        val bsn: String,
    ) : Vertegenwoordigde {
        override val soort get() = Soort.BSN
        override val identificatie get() = bsn

        override fun toString() = "Burger(bsn=***)"
    }

    data class Bedrijf(
        val kvkNummer: String,
        val vestigingsnummer: String? = null,
    ) : Vertegenwoordigde {
        override val soort get() = Soort.KVK
        override val identificatie get() = kvkNummer

        override fun toString() = "Bedrijf(kvkNummer=***, vestigingsnummer=${if (vestigingsnummer == null) "geen" else "***"})"
    }
}

/**
 * De identiteit van een ingelogde gebruiker: de gebruiker en de vertegenwoordigde.
 *
 * Alleen de authenticatiemodule bouwt een identiteit (ArchUnit-regel); alle andere modules lezen hem via
 * [PortalAuthentication.identiteit].
 *
 * De tenant staat niet in de identiteit maar in de sessie (een gecontroleerd attribuut) en in de tenantcontext.
 */
data class Identiteit(
    val gebruiker: Gebruiker,
    val vertegenwoordigde: Vertegenwoordigde,
    /** Machtigingsdienst-UUID's, of `null` als er geen zijn (de BFF levert ze nog niet; machtigen staat uit). */
    val machtigingsdienstUuids: List<UUID>? = null,
)