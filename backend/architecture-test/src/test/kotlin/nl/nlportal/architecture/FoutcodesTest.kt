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

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Contracttest op de foutcodes die de browser te zien krijgt (GraphQL `extensions.code` en REST `code`). De codes staan in
 * `foutcodes.snapshot` (testresources); hernoemen of verwijderen breekt de frontend, dus een wijziging werk je bewust bij.
 * De scan loopt over de bronbestanden van alle modules, zodat een nieuwe REST-handler niet ongezien blijft.
 */
class FoutcodesTest {
    @Test
    fun `the error codes in the production sources equal the snapshot`() {
        val modules =
            System
                .getProperty("architecture.modules")
                .split("|")
                .filter { it.isNotBlank() }
                .map { Path.of(it.substringAfter("=")) }
        val bronnen = Foutcodes.bronnen(modules)
        val snapshot =
            checkNotNull(javaClass.getResourceAsStream("/foutcodes.snapshot")) { "foutcodes.snapshot ontbreekt" }
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }

        Foutcodes.assertGelijk(snapshot, Foutcodes.scan(bronnen))
    }
}