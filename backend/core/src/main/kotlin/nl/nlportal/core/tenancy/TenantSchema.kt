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

import java.time.Duration
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

internal sealed interface SchemaKnoop {
    class Object(
        val eigenschappen: Map<String, Eigenschap>,
    ) : SchemaKnoop

    class Lijst(
        val items: SchemaKnoop,
        val minItems: Int,
    ) : SchemaKnoop

    /** Een map met tekstsleutels; elke waarde heeft de vorm van [waarde]. */
    class Sleutelmap(
        val waarde: SchemaKnoop,
    ) : SchemaKnoop

    class Tekst(
        val patroon: String? = null,
        val opsomming: List<String>? = null,
        /** De tekst is een [GeheimVerwijzing]: een waarde die geen naam is, is vermoedelijk het geheim zelf. */
        val geheimVerwijzing: Boolean = false,
    ) : SchemaKnoop

    object Duur : SchemaKnoop

    object Booleaans : SchemaKnoop

    object Geheel : SchemaKnoop
}

internal class Eigenschap(
    val knoop: SchemaKnoop,
    val verplicht: Boolean,
    /** Mag niet uit de gedeelde basis komen: een credential of sleutelverwijzing van één tenant. */
    val nietErfbaar: Boolean = false,
)

/** Het schema van het tenantbestand: de vaste typen uit core plus de bijdragen van [modules] onder `modules.<naam>`. */
internal class TenantSchemaModel(
    modules: TenantModules,
) {
    val tenantBestand: SchemaKnoop.Object = objectKnoop(TenantBestand::class, "", modules)

    private fun objectKnoop(
        klasse: KClass<*>,
        pad: String,
        modules: TenantModules,
    ): SchemaKnoop.Object =
        SchemaKnoop.Object(
            (
                klasse.primaryConstructor
                    ?: error("${klasse.simpleName} heeft geen primaire constructor en kan niet in het tenantbestand staan")
            ).parameters
                .associate { parameter ->
                    val naam = parameter.name!!
                    val eigenschapPad = if (pad.isEmpty()) naam else "$pad.$naam"
                    naam to
                        Eigenschap(
                            knoop = if (eigenschapPad == MODULES) modulesKnoop(modules) else knoop(parameter.type, eigenschapPad, modules),
                            verplicht = !parameter.isOptional && !parameter.type.isMarkedNullable,
                            nietErfbaar = nietErfbaar(parameter),
                        )
                },
        )

    private fun nietErfbaar(parameter: KParameter) = parameter.type.classifier == GeheimVerwijzing::class || parameter.findAnnotation<NietErfbaar>() != null

    private fun modulesKnoop(modules: TenantModules) =
        SchemaKnoop.Object(
            modules.configuraties.associate {
                it.naam to Eigenschap(objectKnoop(it.klasse, "$MODULES.${it.naam}", modules), verplicht = false)
            },
        )

    private fun knoop(
        type: KType,
        pad: String,
        modules: TenantModules,
    ): SchemaKnoop {
        val klasse = type.classifier as? KClass<*> ?: error("Het type van '$pad' is niet te gebruiken in het tenantbestand")
        return when {
            klasse == GeheimVerwijzing::class -> {
                SchemaKnoop.Tekst(patroon = GeheimVerwijzing.PATROON, geheimVerwijzing = true)
            }

            klasse == String::class -> {
                SchemaKnoop.Tekst(patroon = patroon(pad))
            }

            klasse == Boolean::class -> {
                SchemaKnoop.Booleaans
            }

            klasse == Int::class || klasse == Long::class -> {
                SchemaKnoop.Geheel
            }

            klasse == Duration::class -> {
                SchemaKnoop.Duur
            }

            klasse.java.isEnum -> {
                SchemaKnoop.Tekst(opsomming = klasse.java.enumConstants.map { (it as? TenantbestandWaarde)?.waarde ?: (it as Enum<*>).name })
            }

            klasse == List::class || klasse == Set::class -> {
                SchemaKnoop.Lijst(
                    items = knoop(type.arguments.single().type!!, "$pad[]", modules),
                    minItems = if (pad in NIET_LEEG) 1 else 0,
                )
            }

            klasse == Map::class -> {
                val (sleutel, waarde) = type.arguments.map { it.type!! }
                check(sleutel.classifier == String::class) { "Een map in het tenantbestand heeft tekstsleutels ('$pad')" }
                SchemaKnoop.Sleutelmap(knoop(waarde, "$pad{}", modules))
            }

            klasse.java.isPrimitive || klasse.java.name.startsWith("kotlin.") || klasse.java.name.startsWith("java.") -> {
                error("Het type ${klasse.simpleName} van '$pad' wordt niet ondersteund in het tenantbestand")
            }

            else -> {
                objectKnoop(klasse, pad, modules)
            }
        }
    }

    private fun patroon(pad: String) =
        when (pad) {
            "oin" -> Oin.PATROON
            "domeinen[]" -> HOSTNAAM_PATROON
            else -> null
        }

    companion object {
        /** Domeinen zijn genormaliseerde hostnamen: kleine letters, geen poort, geen trailing dot, geen IP-adres. */
        const val HOSTNAAM_PATROON =
            "^(?=.{1,253}$)(?![0-9.]+$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$"

        private const val MODULES = "modules"
        private val NIET_LEEG = setOf("domeinen", "inlogmiddelen")
    }
}