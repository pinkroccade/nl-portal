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
package nl.nlportal.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * De identiteitsregels (epic #499, ADR 0022 besluit 14): alleen de authenticatiemodule bouwt een identiteit, en
 * andere modules lezen geen claims maar gebruiken de identiteit via de leesroute.
 *
 * [authenticationPackages] zijn de pakketten van de authenticatiemodule en [identityTypes] de typen die een
 * identiteit vormen; ze zijn parameters zodat een overtredende fixture de regels kan bewijzen.
 */
class IdentityRules(
    private val authenticationPackages: Array<String>,
    private val identityTypes: Set<String>,
) {
    private val outsideAuthentication =
        DescribedPredicate.describe<JavaClass>("reside outside the authentication module") { c ->
            authenticationPackages.none { pattern -> matches(c.packageName, pattern) }
        }

    private fun matches(
        packageName: String,
        pattern: String,
    ): Boolean {
        val prefix = pattern.removeSuffix("..")
        return packageName == prefix || packageName.startsWith("$prefix.")
    }

    /** Geen constructor- of `copy`-aanroep van een identiteitstype buiten de authenticatiemodule. */
    fun onlyAuthenticationBuildsAnIdentity(): ArchRule =
        noClasses()
            .that(outsideAuthentication)
            .should()
            .callConstructorWhere(
                DescribedPredicate.describe("construct an identity type") { call: JavaCall<*> -> call.targetOwner.name in identityTypes },
            ).orShould()
            .callMethodWhere(
                DescribedPredicate.describe("copy an identity type") { call: JavaCall<*> ->
                    call.targetOwner.name in identityTypes && call.name.startsWith("copy")
                },
            ).allowEmptyShould(false)
            .because(
                "[N-IDENTITEIT-BOUW] alleen de authenticatiemodule bouwt een identiteit; een andere module die er zelf een " +
                    "maakt of aanpast omzeilt de controle op BSN, KvK-nummer, issuer en subject. Lees de identiteit uit " +
                    "PortalAuthentication.identiteit",
            )

    /** Geen leesaanroep op een token (`Jwt`, `ClaimAccessor`) buiten de authenticatiemodule. */
    fun noClaimReadsOutsideAuthentication(): ArchRule =
        noClasses()
            .that(outsideAuthentication)
            .should()
            .callMethodWhere(
                DescribedPredicate.describe("read a claim from a token") { call: JavaCall<*> ->
                    call.targetOwner.isAssignableTo(CLAIM_ACCESSOR)
                },
            ).allowEmptyShould(false)
            .because(
                "[N-IDENTITEIT-LEZEN] alleen de authenticatiemodule leest claims; andere modules gebruiken " +
                    "PortalAuthentication.identiteit, de enige leesroute",
            )

    companion object {
        private const val CLAIM_ACCESSOR = "org.springframework.security.oauth2.core.ClaimAccessor"

        /** De regels voor de echte code. */
        val production =
            IdentityRules(
                authenticationPackages = arrayOf("nl.nlportal.portal.authentication..", "nl.nlportal.commonground.authentication.."),
                identityTypes =
                    setOf(
                        "nl.nlportal.portal.authentication.domain.Identiteit",
                        "nl.nlportal.portal.authentication.domain.Gebruiker",
                        "nl.nlportal.portal.authentication.domain.Vertegenwoordigde\$Burger",
                        "nl.nlportal.portal.authentication.domain.Vertegenwoordigde\$Bedrijf",
                        "nl.nlportal.portal.authentication.domain.PortalAuthentication",
                    ),
            )
    }
}