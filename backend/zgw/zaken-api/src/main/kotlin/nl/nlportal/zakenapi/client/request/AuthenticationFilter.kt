/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.zakenapi.client.request

import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde

@Suppress("UNCHECKED_CAST")
interface AuthenticationFilter<T : AuthenticationFilter<T>> {
    fun withBsn(bsn: String): T

    fun withKvk(kvk: String): T

    fun withKvkAndVestigingsNummer(
        kvkNummer: String,
        vestigingsNummer: String,
    ): T

    fun withAuthentication(authentication: PortalAuthentication): T {
        when (val vertegenwoordigde = authentication.identiteit.vertegenwoordigde) {
            is Vertegenwoordigde.Burger -> {
                this.withBsn(vertegenwoordigde.identificatie)
            }

            is Vertegenwoordigde.Bedrijf -> {
                val vestigingsNummer = vertegenwoordigde.vestigingsnummer
                if (vestigingsNummer != null) {
                    this.withKvkAndVestigingsNummer(vertegenwoordigde.identificatie, vestigingsNummer)
                } else {
                    this.withKvk(vertegenwoordigde.identificatie)
                }
                //
            }
        }

        return this as T
    }
}