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
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/**
 * Eén beschrijving van het tenantbestand, afgeleid uit de Kotlin-typen van [TenantBestand]. Hieruit komen het
 * gepubliceerde JSON-schema ([TenantSchemaGenerator]) en de controle op onbekende en ontbrekende eigenschappen
 * ([TenantFileLoader]); zo kunnen die niet uit elkaar lopen.
 */
internal sealed interface SchemaKnoop {
    class Object(
        val eigenschappen: Map<String, Eigenschap>,
    ) : SchemaKnoop

    class Lijst(
        val items: SchemaKnoop,
        val minItems: Int,
    ) : SchemaKnoop

    class Tekst(
        val patroon: String? = null,
        val opsomming: List<String>? = null,
    ) : SchemaKnoop

    object Duur : SchemaKnoop

    object Geheelgetal : SchemaKnoop

    object Booleaans : SchemaKnoop
}

internal class Eigenschap(
    val knoop: SchemaKnoop,
    val verplicht: Boolean,
)

internal object TenantSchemaModel {
    /** Domeinen zijn genormaliseerde hostnamen: kleine letters, geen poort, geen trailing dot, geen IP-adres. */
    const val HOSTNAAM_PATROON =
        "^(?=.{1,253}$)(?![0-9.]+$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$"

    private val NIET_LEEG = setOf("domeinen", "inlogmiddelen")

    val tenantBestand: SchemaKnoop.Object by lazy { objectKnoop(TenantBestand::class, "") }

    private fun objectKnoop(
        klasse: KClass<*>,
        pad: String,
    ): SchemaKnoop.Object =
        SchemaKnoop.Object(
            klasse.primaryConstructor!!
                .parameters
                .associate { parameter ->
                    val naam = parameter.name!!
                    val eigenschapPad = if (pad.isEmpty()) naam else "$pad.$naam"
                    naam to
                        Eigenschap(
                            knoop = knoop(parameter.type, eigenschapPad),
                            verplicht = !parameter.isOptional && !parameter.type.isMarkedNullable,
                        )
                },
        )

    private fun knoop(
        type: KType,
        pad: String,
    ): SchemaKnoop {
        val klasse = type.classifier as KClass<*>
        return when {
            klasse == String::class -> {
                SchemaKnoop.Tekst(patroon = patroon(pad))
            }

            klasse == Boolean::class -> {
                SchemaKnoop.Booleaans
            }

            klasse == Int::class -> {
                SchemaKnoop.Geheelgetal
            }

            klasse == Duration::class -> {
                SchemaKnoop.Duur
            }

            klasse.java.isEnum -> {
                SchemaKnoop.Tekst(opsomming = klasse.java.enumConstants.map { (it as TenantbestandWaarde).waarde })
            }

            klasse == List::class -> {
                SchemaKnoop.Lijst(
                    items = knoop(type.arguments.single().type!!, "$pad[]"),
                    minItems = if (pad in NIET_LEEG) 1 else 0,
                )
            }

            else -> {
                objectKnoop(klasse, pad)
            }
        }
    }

    private fun patroon(pad: String) =
        when (pad) {
            "oin" -> Oin.PATROON
            "domeinen[]" -> HOSTNAAM_PATROON
            else -> null
        }
}