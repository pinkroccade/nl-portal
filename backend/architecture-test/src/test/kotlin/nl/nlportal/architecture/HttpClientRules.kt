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
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * De regels voor uitgaand HTTP-verkeer (epic #499, ticket #507): elke uitgaande koppeling loopt via de diepe
 * `KoppelingClient`, die credentials alleen naar de eigen bron stuurt en nooit headers of bodies logt. Buiten
 * `core.koppeling` bouwt niemand een eigen `WebClient`, zet niemand `wiretap` aan en komt het type `WebClient` niet
 * in de bytecode van een module.
 *
 * Alle gegevens zijn parameters zodat een overtredende fixture de regels kan bewijzen.
 *
 * @param koppelingPackages de pakketten waar de HTTP-client wel mag leven.
 * @param notYetMigrated pakketten van modules die nog niet naar de `KoppelingClient` zijn gemigreerd (T9 tot en met T21).
 * Elk migratieticket haalt zijn pakket hier weg; de lijst is een ratchet en mag alleen korter worden.
 * @param clientPackages de pakketten waarmee een module een eigen HTTP-client zou bouwen of aansturen.
 */
class HttpClientRules(
    private val koppelingPackages: Array<String> = arrayOf("nl.nlportal.core.koppeling..", "nl.nlportal.core.modulestatus.."),
    private val notYetMigrated: Array<String> = emptyArray(),
    private val clientPackages: Array<String> = PRODUCTION_CLIENT_PACKAGES,
) {
    private val outsideKoppeling = resideOutsideOfPackages(*koppelingPackages, *notYetMigrated)

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
            .that(outsideKoppeling)
            .should()
            .dependOnClassesThat(resideInAnyPackage(*clientPackages))
            .allowEmptyShould(false)
            .because(
                "modules halen en muteren via de KoppelingClient; een WebClient, ExchangeFilterFunction of HttpClient in een " +
                    "module betekent een tweede, niet-gecontroleerd pad naar een bron",
            )

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
        private const val WEB_CLIENT = "org.springframework.web.reactive.function.client.WebClient"

        private val PRODUCTION_CLIENT_PACKAGES =
            arrayOf(
                "org.springframework.web.reactive.function.client..",
                "org.springframework.boot.webclient..",
                "org.springframework.http.client.reactive..",
                "reactor.netty.http.client..",
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
                "nl.nlportal.documentenapi..",
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