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

import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path

/**
 * Het contract van de foutcodes (ticket #559): leest uit de bronnen welke codes een GraphQL-fout of REST-antwoord kan dragen.
 * Herkend worden `"code" to "X"` (een extensions- of antwoordmap) en `intern(<fout>, [<melding>,] "X")`, de twee vormen
 * waarmee de handlers een code zetten. Een regel is `<oppervlak>: <code>`; het oppervlak is `graphql` voor de module `graphql`
 * en `rest` voor alle andere.
 */
object Foutcodes {
    private val patronen =
        listOf(
            Regex("\"code\"\\s+to\\s+\"([A-Z][A-Z_]+)\""),
            Regex("\\bintern\\(\\s*\\w+\\s*,\\s*(?:\"[^\"]*\"\\s*,\\s*)?\"([A-Z][A-Z_]+)\"\\s*\\)"),
        )

    /** De codes in de bronteksten per oppervlak, gesorteerd en zonder dubbelen. */
    fun scan(bronnen: Map<String, String>): List<String> =
        bronnen
            .flatMap { (oppervlak, tekst) ->
                patronen.flatMap { patroon -> patroon.findAll(tekst).map { "$oppervlak: ${it.groupValues[1]}" }.toList() }
            }.distinct()
            .sorted()

    /** De bronteksten van alle Kotlin-bestanden onder `src/main` van de [modules], samengevoegd per oppervlak. */
    fun bronnen(modules: List<Path>): Map<String, String> =
        modules
            .flatMap { module ->
                val oppervlak = if (module.fileName.toString() == "graphql") "graphql" else "rest"
                val bronmap = module.resolve("src/main")
                if (!Files.isDirectory(bronmap)) {
                    emptyList()
                } else {
                    Files.walk(bronmap).use { paden -> paden.filter { it.toString().endsWith(".kt") }.map { oppervlak to Files.readString(it) }.toList() }
                }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, teksten) -> teksten.joinToString("\n") }

    fun assertGelijk(
        snapshot: List<String>,
        actueel: List<String>,
    ) {
        val verdwenen = snapshot - actueel.toSet()
        val nieuw = actueel - snapshot.toSet()
        assertThat(verdwenen + nieuw)
            .describedAs(
                "Foutcodes wijken af van foutcodes.snapshot. Verdwenen of hernoemd (breekt de frontend): $verdwenen. " +
                    "Nieuw (werk de snapshot bewust bij): $nieuw",
            ).isEmpty()
    }
}