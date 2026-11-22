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
import com.tngtech.archunit.lang.ArchRule
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Bewijst dat elke identiteitsregel een overtreding vangt en een nette module laat staan (fixtures staan alleen in testcode). */
class IdentityRulesProofTest {
    private val base = "nl.nlportal.architecture.fixtures"
    private val rules =
        IdentityRules(
            authenticationPackages = arrayOf("$base.identityvio.auth.."),
            identityTypes = setOf("$base.identityvio.auth.Identity"),
            bearerTokenHolder = "$base.identityvio.auth.Session",
            authenticationModules = setOf("/portal-authentication/"),
        )

    private fun violating() = ClassFileImporter().importPackages("$base.identityvio")

    private fun clean() = ClassFileImporter().importPackages("$base.identityvio.auth", "$base.identityclean")

    private fun assertFailsOn(
        rule: ArchRule,
        vararg classNames: String,
    ) {
        val failure = assertThatThrownBy { rule.check(violating()) }.isInstanceOf(AssertionError::class.java)
        classNames.forEach { failure.hasMessageContaining(it) }
    }

    @Test
    fun `the build rule fails when a module constructs, references or copies an identity`() {
        assertFailsOn(rules.onlyAuthenticationBuildsAnIdentity(), "BuildsIdentity", "BuildsIdentityByReference", "CopiesIdentity")
    }

    @Test
    fun `the token rule fails when a module reads claims, token attributes or a method reference`() {
        assertFailsOn(rules.noTokenHandlingOutsideAuthentication(), "ReadsClaims", "ReadsTokenAttributes", "ReadsClaimsByReference")
    }

    @Test
    fun `the token rule fails when a module parses the raw token itself`() {
        assertFailsOn(rules.noTokenHandlingOutsideAuthentication(), "ParsesBearerToken")
    }

    @Test
    fun `the bearer token rule fails when a module outside the authentication module reads the raw token`() {
        assertFailsOn(rules.onlyNamedReadersUseTheBearerToken(), "ReadsBearerToken")
    }

    @Test
    fun `the ownership rule fails when an authentication package is filled from another module`() {
        assertFailsOn(rules.authenticationPackagesStayInTheirModules(), "Identity")
    }

    @Test
    fun `a named token exception is allowed to handle tokens`() {
        val withException =
            IdentityRules(
                authenticationPackages = arrayOf("$base.identityvio.auth.."),
                identityTypes = setOf("$base.identityvio.auth.Identity"),
                tokenExceptions = setOf("$base.identityvio.feature.ReadsClaims"),
            )

        assertThatThrownBy { withException.noTokenHandlingOutsideAuthentication().check(violating()) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("ReadsTokenAttributes")
            .hasMessageNotContaining("feature.ReadsClaims.")
    }

    @Test
    fun `a module that only reads the identity passes every rule, also with the authentication module reading claims`() {
        val clean = clean()
        val owned =
            IdentityRules(
                authenticationPackages = arrayOf("$base.identityvio.auth.."),
                identityTypes = setOf("$base.identityvio.auth.Identity"),
                authenticationModules = setOf("/architecture-test/"),
            )

        rules.onlyAuthenticationBuildsAnIdentity().check(clean)
        rules.noTokenHandlingOutsideAuthentication().check(clean)
        rules.onlyNamedReadersUseTheBearerToken().check(clean)
        owned.authenticationPackagesStayInTheirModules().check(clean)
    }
}