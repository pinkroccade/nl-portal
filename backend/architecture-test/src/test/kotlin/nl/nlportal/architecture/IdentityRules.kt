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
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.Identiteit
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde

/**
 * De identiteitsregels (epic #499, ADR 0022 besluit 14): alleen de authenticatiemodule bouwt een identiteit,
 * andere modules gaan niet zelf met tokens om maar gebruiken de identiteit via de leesroute, en het ruwe token
 * is alleen voor benoemde lezers bereikbaar.
 *
 * Alle gegevens zijn parameters zodat een overtredende fixture de regels kan bewijzen.
 *
 * @param authenticationPackages de pakketten van de authenticatiemodule. `nl.nlportal.commonground.authentication..`
 * is ook het pakket van de testmodule `common-ground-authentication-test` (`JwtBuilder`, in `src/main`);
 * [authenticationPackagesStayInTheirModules] bewaakt dat alleen [authenticationModules] er klassen in hebben.
 * @param identityTypes de typen die een identiteit vormen.
 * @param tokenPackages de pakketten waarmee een token wordt gelezen of geparsed.
 * @param tokenExceptions klassen buiten de authenticatiemodule die wel met zulke pakketten werken.
 * @param bearerTokenHolder het type dat het ruwe token vasthoudt.
 * @param bearerTokenReaders de pakketten die het ruwe token mogen lezen.
 * @param authenticationModules pad-fragmenten van de modules waarin de authenticatiepakketten mogen staan.
 */
class IdentityRules(
    private val authenticationPackages: Array<String>,
    private val identityTypes: Set<String>,
    private val tokenPackages: Array<String> = PRODUCTION_TOKEN_PACKAGES,
    private val tokenExceptions: Set<String> = emptySet(),
    private val bearerTokenHolder: String = PortalAuthentication::class.java.name,
    private val bearerTokenReaders: Array<String> = emptyArray(),
    private val authenticationModules: Set<String> = emptySet(),
) {
    private val outsideAuthentication = resideOutsideOfPackages(*authenticationPackages)

    private val outsideTokenExceptions =
        DescribedPredicate.describe<JavaClass>("are not a named token exception") { c ->
            tokenExceptions.none { c.name == it || c.name.startsWith("$it$") }
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
                "ADR 0022 besluit 14: alleen de authenticatiemodule bouwt een identiteit; een andere module die er zelf " +
                    "een maakt of aanpast omzeilt de controle op BSN, KvK-nummer, issuer en subject. Lees de identiteit " +
                    "uit PortalAuthentication.identiteit",
            )

    /**
     * Geen afhankelijkheid van een token- of claimtype buiten de authenticatiemodule (en de benoemde uitzonderingen).
     * Een regel op afhankelijkheden in plaats van op aanroepen vangt ook methodreferenties, `tokenAttributes`,
     * `JwtAuthenticationToken.token` en het parsen van het ruwe token met Nimbus.
     */
    fun noTokenHandlingOutsideAuthentication(): ArchRule =
        noClasses()
            .that(outsideAuthentication)
            .and(outsideTokenExceptions)
            .should()
            .dependOnClassesThat(
                resideInAnyPackage(*tokenPackages).or(assignableTo(CLAIM_ACCESSOR)),
            ).allowEmptyShould(false)
            .because(
                "ADR 0022 besluit 14: alleen de authenticatiemodule gaat met tokens en claims om; andere modules " +
                    "gebruiken PortalAuthentication.identiteit, de enige leesroute",
            )

    /** Het ruwe token (`bearerToken`, `credentials`) wordt alleen door de benoemde lezers gelezen. */
    fun onlyNamedReadersUseTheBearerToken(): ArchRule =
        noClasses()
            .that(outsideAuthentication)
            .and(resideOutsideOfPackages(*bearerTokenReaders))
            .should()
            .callMethodWhere(
                DescribedPredicate.describe("read the raw bearer token") { call: JavaCall<*> ->
                    call.targetOwner.name == bearerTokenHolder && call.name in BEARER_TOKEN_GETTERS
                },
            ).allowEmptyShould(false)
            .because(
                "ADR 0022 besluit 14: het ruwe token is geen tweede leesroute naar de identiteit; alleen een benoemde " +
                    "lezer geeft het door aan een bron",
            )

    /** De authenticatiepakketten bestaan alleen in de bedoelde modules: een pakketnaam alleen geeft geen vrijstelling. */
    fun authenticationPackagesStayInTheirModules(): ArchRule =
        classes()
            .that()
            .resideInAnyPackage(*authenticationPackages)
            .should(
                ArchCondition.from(
                    DescribedPredicate.describe("come from an authentication module") { c: JavaClass ->
                        val bron = c.source.map { it.uri.toString() }.orElse("")
                        authenticationModules.any { bron.contains(it) }
                    },
                ),
            ).allowEmptyShould(false)
            .because(
                "de vrijstelling voor de authenticatiepakketten geldt per pakketnaam; een klasse in een andere module " +
                    "met zo'n pakketnaam zou de identiteitsregels omzeilen",
            )

    companion object {
        private const val CLAIM_ACCESSOR = "org.springframework.security.oauth2.core.ClaimAccessor"
        private val BEARER_TOKEN_GETTERS = setOf("getBearerToken", "getCredentials")

        private val PRODUCTION_TOKEN_PACKAGES =
            arrayOf(
                "org.springframework.security.oauth2.jwt..",
                "org.springframework.security.oauth2.server.resource..",
                "org.springframework.security.oauth2.core.user..",
                "com.nimbusds.jwt..",
                "com.nimbusds.jose..",
            )

        /** De regels voor de echte code. */
        val production =
            IdentityRules(
                authenticationPackages = arrayOf("nl.nlportal.portal.authentication..", "nl.nlportal.commonground.authentication.."),
                identityTypes =
                    setOf(
                        Identiteit::class.java.name,
                        Gebruiker::class.java.name,
                        Vertegenwoordigde.Burger::class.java.name,
                        Vertegenwoordigde.Bedrijf::class.java.name,
                        PortalAuthentication::class.java.name,
                    ),
                tokenExceptions =
                    setOf(
                        // Tekent uitgaande ZGW-tokens en client-assertions met Nimbus; leest geen inkomend token.
                        "nl.nlportal.core.koppeling.ZgwJwtGenerator",
                        "nl.nlportal.core.koppeling.ClientAssertion",
                    ),
                authenticationModules =
                    setOf("/portal-authentication/", "/common-ground-authentication/", "/common-ground-authentication-test/"),
            )
    }
}