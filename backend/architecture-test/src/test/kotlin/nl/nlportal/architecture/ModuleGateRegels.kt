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
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

/**
 * De regels van de centrale modulegate (T22, #521, ADR 0026; principe P-07 least functionality): deny by default, en geen gate per resolver.
 *
 * Elke GraphQL-controller en REST-resource draagt `@ModuleEndpoint`: zonder registratie weigert de gate het endpoint, en deze regel
 * laat de build falen voordat dat in een request gebeurt. Een endpoint dat `@ModuleEndpoint` draagt roept `ModuleGate` niet zelf
 * aan: de gate beslist centraal. Een registratie met `overgang = true` is een tussenstap voor een module die nog niet op de gate
 * staat; zo'n module moet in [overgang] staan, en die lijst mag alleen korter worden (elke module die overgaat haalt zijn naam
 * hier weg).
 *
 * @param overgang de modulenamen die nog met `overgang = true` mogen registreren.
 */
class ModuleGateRegels(
    private val overgang: Set<String> = emptySet(),
) {
    private fun JavaClass.registratie() = tryGetAnnotationOfType(MODULE_ENDPOINT).orElse(null)

    /** Elke controller of resource is bij de gate geregistreerd. */
    fun everyEndpointIsRegistered(): ArchRule =
        classes()
            .that()
            .areAnnotatedWith(CONTROLLER)
            .or()
            .areMetaAnnotatedWith(CONTROLLER)
            .should(
                object : ArchCondition<JavaClass>("be registered at the module gate with @ModuleEndpoint") {
                    override fun check(
                        item: JavaClass,
                        events: ConditionEvents,
                    ) {
                        events.add(SimpleConditionEvent(item, item.registratie() != null, "${item.name} draagt geen @ModuleEndpoint: de gate weigert het endpoint"))
                    }
                },
            ).allowEmptyShould(false)
            .because("een module-endpoint zonder registratie bij de gate is niet bereikbaar (deny by default, ADR 0026)")

    /** Een geregistreerd endpoint beslist de gate niet zelf: geen `ModuleGate` als dependency. */
    fun registeredEndpointsDoNotCallTheGate(): ArchRule =
        classes()
            .that(
                object : DescribedPredicate<JavaClass>("are registered at the module gate") {
                    override fun test(klasse: JavaClass) = klasse.registratie() != null
                },
            ).should(
                object : ArchCondition<JavaClass>("not depend on ModuleGate") {
                    override fun check(
                        item: JavaClass,
                        events: ConditionEvents,
                    ) {
                        val gebruikt = item.directDependenciesFromSelf.any { it.targetClass.name == MODULE_GATE }
                        events.add(SimpleConditionEvent(item, !gebruikt, "${item.name} gebruikt ModuleGate zelf; de centrale gate beslist, een resolver roept hem niet aan"))
                    }
                },
            ).allowEmptyShould(false)
            .because("een vergeten of verkeerde aanroep per resolver is een open endpoint; de gate beslist centraal (ADR 0026)")

    /** Alleen een module in [overgang] mag met `overgang = true` registreren: de lijst mag alleen korter worden. */
    fun onlyListedModulesMayBeTransitional(): ArchRule =
        classes()
            .that()
            .areAnnotatedWith(MODULE_ENDPOINT)
            .should(
                object : ArchCondition<JavaClass>("be transitional only for a module in the transition list") {
                    override fun check(
                        item: JavaClass,
                        events: ConditionEvents,
                    ) {
                        val registratie = item.registratie() ?: return
                        val isOvergang = registratie.get("overgang").orElse(false) as Boolean
                        val module = registratie.get("module").orElse("") as String
                        val toegestaan = !isOvergang || module in overgang
                        events.add(SimpleConditionEvent(item, toegestaan, "${item.name} registreert module '$module' met overgang = true, maar die module staat niet (meer) in ModuleGateRegels.OVERGANG; zet hem op de gate"))
                    }
                },
            ).allowEmptyShould(false)
            .because("de overgangslijst mag alleen korter worden: elke module die overgaat haalt zijn naam eruit")

    companion object {
        private const val CONTROLLER = "org.springframework.stereotype.Controller"
        private const val MODULE_ENDPOINT = "nl.nlportal.core.modulegate.ModuleEndpoint"
        private const val MODULE_GATE = "nl.nlportal.core.modulegate.ModuleGate"

        /** Het maximale aantal modules in [OVERGANG]; alleen dalen (ratchet), [ModuleGateRegelsTest] pint het vast. */
        const val OVERGANG_MAX = 2

        /** De modules die nog niet op de gate staan (#521-vervolg); elke overgang haalt zijn naam hier weg. */
        val OVERGANG = setOf("openklant", "openproduct")

        /** De regels voor de echte code. */
        val production = ModuleGateRegels(overgang = OVERGANG)
    }
}