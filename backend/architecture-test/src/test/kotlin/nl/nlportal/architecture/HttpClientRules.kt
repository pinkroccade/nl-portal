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
package nl.nlportal.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields

/**
 * De regels voor uitgaand HTTP-verkeer (epic #499, ticket #507): elke uitgaande koppeling loopt via de diepe
 * `KoppelingClient`, die credentials alleen naar de eigen bron stuurt en nooit headers of bodies logt. Buiten
 * `core.koppeling` bouwt niemand een eigen `WebClient`, zet niemand `wiretap` aan en komt het type `WebClient` niet
 * in de bytecode van een module.
 *
 * Alle gegevens zijn parameters zodat een overtredende fixture de regels kan bewijzen.
 *
 * @param koppelingPackages de pakketten waar de HTTP-client wel mag leven; vrijgesteld van alle regels.
 * @param webClientTypeExempt pakketten die alleen het type van een WebClient-hulpklasse mogen kennen (nu het
 * `ExchangeFilterFunction` van `core.modulestatus`); ze zijn vrijgesteld van [noWebClientInModules] en van niets anders.
 * @param koppelingBronpad deel van het bestandspad waaraan blijkt dat een koppelingspakket in de `core`-module van de backend staat (`/backend/core/build/`, dus niet in een geneste module die toevallig `core` heet).
 * @param notYetMigrated pakketten van modules die nog niet naar de `KoppelingClient` zijn gemigreerd (T9 tot en met T21).
 * Elk migratieticket haalt zijn pakket hier weg; de lijst is een ratchet en mag alleen korter worden.
 * @param clientPackages de pakketten waarmee een module een eigen HTTP-client zou bouwen of aansturen.
 */
class HttpClientRules(
    private val koppelingPackages: Array<String> = arrayOf("nl.nlportal.core.koppeling.."),
    private val notYetMigrated: Array<String> = emptyArray(),
    private val clientPackages: Array<String> = PRODUCTION_CLIENT_PACKAGES,
    private val webClientTypeExempt: Array<String> = arrayOf("nl.nlportal.core.modulestatus.."),
    private val koppelingBronpad: String = "/backend/core/build/",
) {
    private val outsideKoppeling = resideOutsideOfPackages(*koppelingPackages, *notYetMigrated)
    private val outsideKoppelingEnTypeVrijstelling = resideOutsideOfPackages(*koppelingPackages, *notYetMigrated, *webClientTypeExempt)

    /** Geen `WebClient.create()` of `WebClient.builder()` buiten `core.koppeling`. */
    fun noOwnWebClient(): ArchRule =
        noClasses()
            .that(outsideKoppeling)
            .should()
            .callMethodWhere(
                DescribedPredicate.describe("build a WebClient") { call: JavaCall<*> ->
                    call.targetOwner.name == WEB_CLIENT && call.name in setOf("create", "builder")
                },
            ).allowEmptyShould(false)
            .because(
                "elke uitgaande koppeling loopt via de KoppelingClient (host-binding, foutvorm, limiet en circuit breaker per " +
                    "tenant en koppeling); een eigen WebClient omzeilt dat en kan credentials naar een andere host sturen",
            )

    /** Het type `WebClient` (en zijn hulptypen) komt niet in de bytecode van een module; alleen `core.koppeling` kent het. */
    fun noWebClientInModules(): ArchRule =
        noClasses()
            .that(outsideKoppelingEnTypeVrijstelling)
            .should()
            .dependOnClassesThat(resideInAnyPackage(*clientPackages))
            .orShould()
            .dependOnClassesThat(JavaClass.Predicates.assignableTo(java.net.URLConnection::class.java))
            .orShould()
            .callMethodWhere(
                DescribedPredicate.describe("open a raw connection with URL.openConnection, openStream or getContent") { call: JavaCall<*> ->
                    call.targetOwner.name == "java.net.URL" && call.name in setOf("openConnection", "openStream", "getContent")
                },
            ).allowEmptyShould(false)
            .because(
                "modules halen en muteren via de KoppelingClient; een WebClient, ExchangeFilterFunction of HttpClient in een " +
                    "module betekent een tweede, niet-gecontroleerd pad naar een bron",
            )

    /** Geen veld van het type `KoppelingClient` buiten het koppelingspakket: een bewaarde client zou voor elke tenant de eerste tenant gebruiken. */
    fun noStoredKoppelingClient(): ArchRule =
        noFields()
            .that()
            .areDeclaredInClassesThat(outsideKoppeling)
            .should()
            .haveRawType(KOPPELING_CLIENT)
            .allowEmptyShould(true)
            .because("een KoppelingClient hoort bij één tenant; haal hem per aanroep op via het register in plaats van hem in een veld te bewaren")

    /** De koppelingspakketten bestaan alleen in de `core`-module: een andere module kan de vrijstelling niet claimen met dezelfde pakketnaam. */
    fun koppelingPackagesOnlyInCore(): ArchRule =
        classes()
            .that()
            .resideInAnyPackage(*koppelingPackages)
            .should(
                object : ArchCondition<JavaClass>("be located in the core module") {
                    override fun check(
                        item: JavaClass,
                        events: ConditionEvents,
                    ) {
                        val pad = item.source.map { it.uri.toString() }.orElse("")
                        events.add(SimpleConditionEvent(item, pad.contains(koppelingBronpad), "${item.name} is located at $pad"))
                    }
                },
            ).allowEmptyShould(false)
            .because("de vrijstelling geldt op pakketnaam; een koppelingspakket in een andere module zou de regels omzeilen")

    /** Geeft de pakketten uit [notYetMigrated] waarin geen enkele klasse nog een regel overtreedt: die horen uit de lijst (ratchet). */
    fun staleExemptions(classes: JavaClasses): List<String> {
        val strikt = HttpClientRules(koppelingPackages, emptyArray(), clientPackages, webClientTypeExempt, koppelingBronpad)
        val regels = listOf(strikt.noOwnWebClient(), strikt.noWebClientInModules(), strikt.noWiretap(), strikt.noRequestDetailLogging())
        return notYetMigrated.filter { pakket ->
            val inPakket = classes.that(resideInAnyPackage(pakket))
            // Een pakket zonder klassen (module verwijderd of hernoemd) is ook verouderd: de regels zouden hem niet zien.
            inPakket.isEmpty() || regels.none { it.evaluate(inPakket).hasViolation() }
        }
    }

    /** Geen `wiretap` op een Reactor Netty-client of -transport: dat logt alle headers en bodies, dus ook tokens en BSN's. */
    fun noWiretap(): ArchRule =
        noClasses()
            .that(outsideKoppeling)
            .should()
            .callMethodWhere(
                DescribedPredicate.describe("enable wiretap") { call: JavaCall<*> ->
                    call.name == "wiretap" && call.targetOwner.packageName.startsWith("reactor.netty")
                },
            ).allowEmptyShould(false)
            .because("wiretap logt alle headers en bodies van het verkeer met een bron, inclusief tokens en persoonsgegevens")

    /** Geen `enableLoggingRequestDetails`: dat zet headers en bodies van verzoeken in de log. */
    fun noRequestDetailLogging(): ArchRule =
        noClasses()
            .that(outsideKoppeling)
            .should()
            .callMethodWhere(
                DescribedPredicate.describe("enable logging of request details") { call: JavaCall<*> ->
                    call.name == "enableLoggingRequestDetails"
                },
            ).allowEmptyShould(false)
            .because("headers en bodies van verzoeken mogen niet in de log: ze bevatten tokens en persoonsgegevens")

    companion object {
        private const val KOPPELING_CLIENT = "nl.nlportal.core.koppeling.KoppelingClient"
        private const val WEB_CLIENT = "org.springframework.web.reactive.function.client.WebClient"

        private val PRODUCTION_CLIENT_PACKAGES =
            arrayOf(
                "org.springframework.web.reactive.function.client..",
                "org.springframework.boot.webclient..",
                "org.springframework.http.client.reactive..",
                "reactor.netty.http.client..",
                "org.springframework.web.client..",
                "org.springframework.web.service..",
                "java.net.http..",
                "okhttp3..",
                "org.apache.hc..",
                "org.eclipse.jetty.client..",
            )

        /**
         * Modules die nog een eigen client bouwen. Elk migratieticket (T9 tot en met T21) haalt zijn pakket hier weg;
         * deze lijst mag alleen korter worden.
         */
        val NOT_YET_MIGRATED =
            arrayOf(
                "nl.nlportal.berichten..",
                "nl.nlportal.besluiten..",
                "nl.nlportal.catalogiapi..",
                "nl.nlportal.commonground.authentication..",
                "nl.nlportal.form..",
                "nl.nlportal.haalcentraalbrp..",
                "nl.nlportal.kvk..",
                "nl.nlportal.openklant..",
                "nl.nlportal.openproduct..",
                "nl.nlportal.payment.direct..",
                "nl.nlportal.verificatie..",
                "nl.nlportal.zakenapi..",
                "nl.nlportal.zgw.objectenapi..",
                "nl.nlportal.zgw.taak..",
            )

        /** De regels voor de echte code. */
        val production = HttpClientRules(notYetMigrated = NOT_YET_MIGRATED)
    }
}