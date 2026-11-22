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
package nl.nlportal.portal.authentication.domain

import nl.nlportal.core.featureflag.UserTypeAware
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.authentication.AbstractAuthenticationToken

/**
 * De authenticatie van een ingelogde gebruiker, zoals de BFF hem uit de sessie opbouwt. De enige leesroute naar de
 * identiteit is [identiteit]. Er is geen token: modules krijgen nooit een token te zien ([getCredentials] is leeg).
 */
class PortalAuthentication(
    val identiteit: Identiteit,
    authorities: Collection<GrantedAuthority>,
) : AbstractAuthenticationToken(authorities),
    UserTypeAware {
    init {
        super.setAuthenticated(true)
    }

    override val userType: String get() = identiteit.vertegenwoordigde.soort.code

    override val subject: String get() = identiteit.gebruiker.subject

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = identiteit.gebruiker.subject
}