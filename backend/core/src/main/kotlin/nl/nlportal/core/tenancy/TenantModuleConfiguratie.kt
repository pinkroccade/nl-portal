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
package nl.nlportal.core.tenancy

import java.util.ServiceLoader
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/** Een fout in de configuratie van een module; [pad] is relatief aan `modules.<naam>`. Het bericht bevat nooit een waarde. */
data class ModuleOvertreding(
    val pad: String,
    val bericht: String,
)

/** Wat een module over de tenant weet bij het valideren van zijn eigen configuratie: de namen van de koppelingen in het tenantbestand. */
data class ModuleContext(
    val koppelingen: Set<String> = emptySet(),
)

/**
 * Het haakpunt waarmee een module zijn getypeerde configuratieklasse bijdraagt aan het tenantbestand als `modules.<naam>`
 * (epic #499, "Core-seam"). Schema, loader, validator-CLI en de opstartvalidatie nemen elke bijdrage mee, zonder dat core
 * de module kent.
 *
 * Registratie is via [ServiceLoader], zodat ook de validator-CLI (zonder Spring) de bijdrage vindt: zet de naam van een
 * klasse met een publieke constructor zonder parameters (geen Kotlin-`object`) in
 * `META-INF/services/nl.nlportal.core.tenancy.TenantModuleConfiguratie` van de module.
 *
 * De [klasse] is een Kotlin-dataclass; het schema volgt zijn primaire constructor (typen: tekst, getal, boolean, [java.time.Duration],
 * enum, [GeheimVerwijzing], lijst, verzameling, map met tekstsleutels en geneste dataclasses). Een eigenschap zonder
 * standaardwaarde en niet nullable is verplicht. Een enum implementeert [TenantbestandWaarde] voor een eigen spelling, anders geldt de naam.
 * Een geheim is altijd een [GeheimVerwijzing]; credentials die geen verwijzing zijn krijgen [NietErfbaar].
 */
interface TenantModuleConfiguratie<T : Any> {
    /** De sleutel onder `modules`: kleine letters, cijfers en streepjes, beginnend met een letter. */
    val naam: String

    val klasse: KClass<T>

    /** Regels over de samenhang van de waarden, bovenop de vorm; wordt bij het laden en bij de validator-CLI toegepast. */
    fun valideer(configuratie: T): List<ModuleOvertreding> = emptyList()

    /**
     * Als [valideer], met de [context] van de tenant: voor een regel als "de koppeling die dit veld noemt bestaat". Een module
     * overschrijft de ene of de andere; deze standaard geeft de context niet door.
     */
    fun valideer(
        configuratie: T,
        context: ModuleContext,
    ): List<ModuleOvertreding> = valideer(configuratie)
}

/** De bijdragen van alle modules die op het klassenpad staan, gevalideerd op naam en vorm. */
class TenantModules private constructor(
    val configuraties: List<TenantModuleConfiguratie<*>>,
) {
    private val perNaam = configuraties.associateBy { it.naam }

    fun configuratie(naam: String): TenantModuleConfiguratie<*>? = perNaam[naam]

    /** Valideert [configuratie], gebonden onder [naam]; het type klopt omdat de loader het met [TenantModuleConfiguratie.klasse] bond. */
    @Suppress("UNCHECKED_CAST")
    internal fun valideer(
        naam: String,
        configuratie: Any,
        context: ModuleContext,
    ): List<ModuleOvertreding> = (perNaam.getValue(naam) as TenantModuleConfiguratie<Any>).valideer(configuratie, context)

    companion object {
        private val NAAM = Regex("^[a-z][a-z0-9-]*$")

        val GEEN = TenantModules(emptyList())

        /** Zoekt de bijdragen via [ServiceLoader] in [classLoader]. */
        fun ontdek(classLoader: ClassLoader? = TenantModules::class.java.classLoader): TenantModules {
            @Suppress("UNCHECKED_CAST")
            val gevonden = ServiceLoader.load(TenantModuleConfiguratie::class.java, classLoader).toList() as List<TenantModuleConfiguratie<*>>
            return van(gevonden)
        }

        /** Voor tests en voor wie de bijdragen zelf aanlevert; weigert een ongeldige of dubbele naam, een gedeelde klasse en een klasse zonder primaire constructor. */
        fun van(configuraties: List<TenantModuleConfiguratie<*>>): TenantModules {
            configuraties.forEach {
                check(it.naam.matches(NAAM)) { "Modulenaam '${it.naam}' is ongeldig: kleine letters, cijfers en streepjes, beginnend met een letter" }
                check(it.klasse.primaryConstructor != null) { "De configuratieklasse van module '${it.naam}' heeft geen primaire constructor" }
            }
            configuraties.groupBy { it.naam }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
                error("Meerdere modules dragen de naam '$it' bij aan het tenantbestand")
            }
            // Een module zoekt zijn configuratie op klasse (TenantDefinition.module): twee modules met een klasse zouden elkaars gegevens lezen.
            configuraties.groupBy { it.klasse }.values.firstOrNull { it.size > 1 }?.let { dubbel ->
                error("De modules ${dubbel.joinToString { "'${it.naam}'" }} gebruiken dezelfde configuratieklasse; elke module heeft een eigen klasse")
            }
            return TenantModules(configuraties.sortedBy { it.naam })
        }
    }
}