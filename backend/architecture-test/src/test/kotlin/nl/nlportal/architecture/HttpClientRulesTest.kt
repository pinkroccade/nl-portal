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
import org.junit.jupiter.api.Test

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
    fun `no module turns wiretap on`() {
        HttpClientRules.production.noWiretap().check(classes)
    }

    @Test
    fun `no module logs request details`() {
        HttpClientRules.production.noRequestDetailLogging().check(classes)
    }
}