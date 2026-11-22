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
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.lang.AbstractClassesTransformer
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.all

/**
 * Mechanische migratieregels uit de retro van epic #499 (ticket #559).
 *
 * Een module draagt een moduleconfiguratie of koppeling bij als een van haar klassen `TenantModuleConfiguratie`
 * implementeert of het `KoppelingRegister` gebruikt. Zo'n module registreert een `VervallenProperties`-bean voor haar
 * oude properties: zonder die bean weigert core het opstarten niet bij een nog gezette oude instelling. De bean staat in de
 * autoconfiguratie van de module; de module is het pakket boven `autoconfigure`/`autoconfiguration`.
 *
 * @param notYetMigrated nog niet gemigreerde pakketten, vrijgesteld; de lijst van [HttpClientRules.NOT_YET_MIGRATED],
 * zodat er één bron voor de uitzonderingen is.
 * @param corePackages pakketten van core zelf, die geen module zijn.
 */
class MigratieRegels(
    private val notYetMigrated: Array<String> = emptyArray(),
    private val corePackages: Array<String> = arrayOf("nl.nlportal.core.."),
) {
    private val contributor =
        DescribedPredicate.describe<JavaClass>("contribute a module configuration or koppeling") { klasse ->
            !resideInAnyPackage(*corePackages, *notYetMigrated).test(klasse) &&
                (
                    klasse.allRawInterfaces.any { it.name == TENANT_MODULE_CONFIGURATIE } ||
                        klasse.directDependenciesFromSelf.any { it.targetClass.name == KOPPELING_REGISTER }
                )
        }

    /** Een klasse die bijdraagt, met de uitkomst of haar module een `VervallenProperties`-bean registreert. */
    data class Bijdrage(
        val klasse: JavaClass,
        val gedekt: Boolean,
    )

    // De transformer ziet alle klassen; een ArchCondition krijgt alleen de klassen die door that() komen, en de bean staat in een andere klasse.
    private val bijdragen =
        object : AbstractClassesTransformer<Bijdrage>("contributors of a module configuration or koppeling") {
            override fun doTransform(klassen: JavaClasses): Iterable<Bijdrage> {
                val moduleRoots =
                    klassen
                        .filter { klasse ->
                            klasse.methods.any { it.isAnnotatedWith(BEAN) && it.rawReturnType.name == VERVALLEN_PROPERTIES }
                        }.map { it.packageName.replace(Regex("\\.autoconfigur(e|ation)(\\..*)?$"), "") }
                return klassen.filter(contributor::test).map { klasse ->
                    Bijdrage(klasse, moduleRoots.any { klasse.packageName == it || klasse.packageName.startsWith("$it.") })
                }
            }
        }

    /** Elke module die een moduleconfiguratie of koppeling bijdraagt, registreert een `VervallenProperties`-bean. */
    fun contributorsRegisterVervallenProperties(): ArchRule =
        all(bijdragen)
            .should(
                object : ArchCondition<Bijdrage>("live in a module that registers a VervallenProperties bean") {
                    override fun check(
                        item: Bijdrage,
                        events: ConditionEvents,
                    ) {
                        events.add(SimpleConditionEvent(item, item.gedekt, "${item.klasse.name} draagt bij, maar zijn module registreert geen VervallenProperties-bean"))
                    }
                },
            ).allowEmptyShould(false)
            .because(
                "zonder VervallenProperties-bean weigert core het opstarten niet bij een nog gezette oude instelling, " +
                    "en een beheerder merkt niet dat die niets meer doet",
            )

    companion object {
        private const val TENANT_MODULE_CONFIGURATIE = "nl.nlportal.core.tenancy.TenantModuleConfiguratie"
        private const val KOPPELING_REGISTER = "nl.nlportal.core.koppeling.KoppelingRegister"
        private const val VERVALLEN_PROPERTIES = "nl.nlportal.core.tenancy.VervallenProperties"
        private const val BEAN = "org.springframework.context.annotation.Bean"

        /** De regels voor de echte code; de uitzonderingen zijn de nog niet gemigreerde modules van [HttpClientRules]. */
        val production = MigratieRegels(notYetMigrated = HttpClientRules.NOT_YET_MIGRATED)
    }
}