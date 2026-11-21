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

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** De HTTP-clientregels op de echte code. Het bewijs dat ze een overtreding vangen staat in [HttpClientRulesProofTest]. */
class HttpClientRulesTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("nl.nlportal")

    @Test
    fun `no module builds its own WebClient`() {
        HttpClientRules.production.noOwnWebClient().check(classes)
    }

    @Test
    fun `no module holds a WebClient type`() {
        HttpClientRules.production.noWebClientInModules().check(classes)
    }

    @Test
    fun `no module keeps a KoppelingClient in a field`() {
        HttpClientRules.production.noStoredKoppelingClient().check(classes)
    }

    @Test
    fun `the koppeling packages exist only in the core module`() {
        HttpClientRules.production.koppelingPackagesOnlyInCore().check(classes)
    }

    @Test
    fun `every package in the not yet migrated list still has a violation`() {
        assertThat(HttpClientRules.production.staleExemptions(classes)).isEmpty()
    }

    @Test
    fun `no production configuration turns on client wiretap or request detail logging`() {
        val verboden =
            listOf(
                Regex("""reactor\.netty\.http\.client[\w.]*\s*:\s*(DEBUG|TRACE)""", RegexOption.IGNORE_CASE),
                Regex("""org\.springframework\.web\.reactive\.function\.client[\w.]*\s*:\s*TRACE""", RegexOption.IGNORE_CASE),
                Regex("""log-request-details\s*:\s*true""", RegexOption.IGNORE_CASE),
            )
        val backend = Path.of("..").toAbsolutePath().normalize()
        val bestanden =
            Files.walk(backend).use { paden ->
                paden
                    .filter { Files.isRegularFile(it) }
                    .map { backend.relativize(it).toString().replace('\\', '/') to it }
                    .filter { (relatief, _) -> !relatief.contains("/build/") && relatief.contains("/src/main/resources/") }
                    .filter { (relatief, _) -> relatief.substringAfterLast('/').matches(Regex("""application.*\.ya?ml""")) }
                    .map { it.second }
                    .toList()
            }

        assertThat(bestanden).isNotEmpty()
        bestanden.forEach { bestand ->
            val inhoud = Files.readString(bestand)
            verboden.forEach { assertThat(it.containsMatchIn(inhoud)).describedAs("$bestand bevat ${it.pattern}").isFalse() }
        }
    }

    @Test
    fun `no module turns wiretap on`() {
        HttpClientRules.production.noWiretap().check(classes)
    }

    @Test
    fun `no module logs request details`() {
        HttpClientRules.production.noRequestDetailLogging().check(classes)
    }
}