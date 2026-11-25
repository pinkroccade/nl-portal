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
package nl.nlportal.commonground.authentication

import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.Identiteit
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import java.util.UUID

/**
 * Bouwt de authenticatie van een ingelogde gebruiker voor tests, zoals de BFF hem na de callback in de sessie zet
 * (er is geen token meer). De naam is een erfenis van de tijd dat dit een JWT bouwde.
 */
class JwtBuilder {
    private var issuer: String = ISSUER
    private var subject: String = SUBJECT
    private var vertegenwoordigde: Vertegenwoordigde? = null
    private var machtigingsdienstUuids: List<UUID>? = null

    fun issuer(issuer: String): JwtBuilder {
        this.issuer = issuer
        return this
    }

    fun subject(subject: String): JwtBuilder {
        this.subject = subject
        return this
    }

    fun vertegenwoordigdeBsn(bsn: String): JwtBuilder {
        assert(vertegenwoordigde == null || vertegenwoordigde is Vertegenwoordigde.Burger) { "cannot set bsn for an authentication that already has kvk" }

        vertegenwoordigde = Vertegenwoordigde.Burger(bsn)

        return this
    }

    fun vertegenwoordigdeKvk(kvk: String): JwtBuilder {
        assert(vertegenwoordigde == null || vertegenwoordigde is Vertegenwoordigde.Bedrijf) { "cannot set kvk for an authentication that already has bsn" }

        vertegenwoordigde = Vertegenwoordigde.Bedrijf(kvk)

        return this
    }

    fun vestigingsNummerKvk(
        kvk: String,
        vestigingsNummer: String,
    ): JwtBuilder {
        vertegenwoordigde = Vertegenwoordigde.Bedrijf(kvk, vestigingsNummer)

        return this
    }

    fun machtingDienstKvk(machtingDienst: String): JwtBuilder {
        machtigingsdienstUuids = listOf(UUID.fromString(machtingDienst))

        return this
    }

    /** De authenticatie via dezelfde klasse als in productie. */
    fun buildAuthentication(): PortalAuthentication {
        val vertegenwoordigde = checkNotNull(vertegenwoordigde) { "vertegenwoordigde needs to be set with either bsn or kvk" }
        return PortalAuthentication(
            Identiteit(
                gebruiker = Gebruiker(issuer, subject, Inlogmiddel.GEBRUIKERSNAAM, Betrouwbaarheidsniveau.BASIS),
                vertegenwoordigde = vertegenwoordigde,
                machtigingsdienstUuids = machtigingsdienstUuids,
            ),
            emptyList(),
        )
    }

    companion object {
        const val ISSUER = "https://login.example.nl/realms/portal"
        const val SUBJECT = "c0ffee00-0000-4000-8000-000000000001"
    }
}