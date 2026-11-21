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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** De identiteitsregels op de echte code, en het bewijs dat ze een overtreding vangen. */
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
    fun `no module outside authentication reads claims`() {
        IdentityRules.production.noClaimReadsOutsideAuthentication().check(classes)
    }

    private val base = "nl.nlportal.architecture.fixtures.identity"
    private val fixtureRules =
        IdentityRules(
            authenticationPackages = arrayOf("$base.auth.."),
            identityTypes = setOf("$base.auth.Identity"),
        )

    private fun fixtures(pkg: String) = ClassFileImporter().importPackages("$base.auth", "$base.$pkg")

    @Test
    fun `the build rule fails when a module constructs an identity itself`() {
        assertThatThrownBy { fixtureRules.onlyAuthenticationBuildsAnIdentity().check(fixtures("feature")) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("N-IDENTITEIT-BOUW")
            .hasMessageContaining("BuildsIdentity")
    }

    @Test
    fun `the build rule fails when a module copies an identity`() {
        assertThatThrownBy { fixtureRules.onlyAuthenticationBuildsAnIdentity().check(fixtures("feature")) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("CopiesIdentity")
    }

    @Test
    fun `the read rule fails when a module reads claims from a token`() {
        assertThatThrownBy { fixtureRules.noClaimReadsOutsideAuthentication().check(fixtures("feature")) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("N-IDENTITEIT-LEZEN")
            .hasMessageContaining("ReadsClaims")
    }

    @Test
    fun `a module that only reads the identity passes both rules, also with the authentication module reading claims`() {
        val clean = fixtures("clean")

        fixtureRules.onlyAuthenticationBuildsAnIdentity().check(clean)
        fixtureRules.noClaimReadsOutsideAuthentication().check(clean)
    }
}