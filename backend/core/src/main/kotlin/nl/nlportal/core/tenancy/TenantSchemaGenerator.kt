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

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** Genereert het publieke JSON-schema van het tenantbestand uit dezelfde typen die de validator gebruikt. */
object TenantSchemaGenerator {
    const val SCHEMA_ID = "https://nl-portal.nl/schema/tenant.schema.json"

    private val mapper = JsonMapper.builder().build()

    /** Het schema van het tenantbestand met de bijdragen van [modules] (standaard: alles op het klassenpad). */
    fun generate(modules: TenantModules = TenantModules.ontdek()): String {
        val schema =
            linkedMapOf<String, Any?>(
                "\$schema" to "https://json-schema.org/draft/2020-12/schema",
                "\$id" to SCHEMA_ID,
                "title" to "NL Portal tenantbestand",
                "description" to
                    "Niet-geheime inrichting van een tenant in een omgeving. Een OIN moet tussen aanhalingstekens staan. " +
                    "Het bestand heet <oin>.yaml; base.yaml in dezelfde map is de gedeelde basis voor die omgeving.",
            ) + objectSchema(TenantSchemaModel(modules).tenantBestand)
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema).replace("\r\n", "\n") + "\n"
    }

    private fun schema(knoop: SchemaKnoop): Map<String, Any?> =
        when (knoop) {
            is SchemaKnoop.Object -> {
                objectSchema(knoop)
            }

            is SchemaKnoop.Lijst -> {
                linkedMapOf<String, Any?>("type" to "array", "items" to schema(knoop.items)).also {
                    if (knoop.minItems > 0) it["minItems"] = knoop.minItems
                }
            }

            is SchemaKnoop.Tekst -> {
                linkedMapOf<String, Any?>("type" to "string").also {
                    knoop.patroon?.let { patroon -> it["pattern"] = patroon }
                    knoop.opsomming?.let { opsomming -> it["enum"] = opsomming }
                    if (knoop.patroon == null && knoop.opsomming == null) it["minLength"] = 1
                }
            }

            is SchemaKnoop.Sleutelmap -> {
                linkedMapOf<String, Any?>("type" to "object", "additionalProperties" to schema(knoop.waarde))
            }

            SchemaKnoop.Waar -> {
                mapOf("type" to "boolean")
            }

            SchemaKnoop.Geheel -> {
                mapOf("type" to "integer")
            }

            SchemaKnoop.Duur -> {
                mapOf("type" to "string", "pattern" to DUUR_PATROON, "description" to "ISO-8601-duur, bijvoorbeeld PT15M")
            }
        }

    private fun objectSchema(knoop: SchemaKnoop.Object): LinkedHashMap<String, Any?> {
        val result = linkedMapOf<String, Any?>("type" to "object")
        result["properties"] = knoop.eigenschappen.mapValuesTo(linkedMapOf()) { schema(it.value.knoop) }
        val verplicht =
            knoop.eigenschappen
                .filterValues { it.verplicht }
                .keys
                .toList()
        if (verplicht.isNotEmpty()) result["required"] = verplicht
        result["additionalProperties"] = false
        return result
    }

    private const val DUUR_PATROON = "^P(?!$)(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+S)?)?$"

    /** Schrijft het schema naar [doel]; aangeroepen door de build (`mise run backend:tenant-schema`, in :app waar alle modules op het klassenpad staan). */
    @JvmStatic
    fun main(args: Array<String>) {
        val doel = Path.of(args.firstOrNull() ?: error("Geef het doelbestand op"))
        doel.parent?.let { Files.createDirectories(it) }
        Files.writeString(doel, generate())
    }
}