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

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test

/** De identiteitsregels op de echte code. Het bewijs dat ze een overtreding vangen staat in [IdentityRulesProofTest]. */
class IdentityRulesTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("nl.nlportal")

    @Test
    fun `only the authentication module builds an identity`() {
        IdentityRules.production.onlyAuthenticationBuildsAnIdentity().check(classes)
    }

    @Test
    fun `no module outside authentication handles tokens or claims`() {
        IdentityRules.production.noTokenHandlingOutsideAuthentication().check(classes)
    }

    @Test
    fun `only the named readers use the raw bearer token`() {
        IdentityRules.production.onlyNamedReadersUseTheBearerToken().check(classes)
    }

    @Test
    fun `the authentication packages only exist in the authentication modules`() {
        IdentityRules.production.authenticationPackagesStayInTheirModules().check(classes)
    }
}