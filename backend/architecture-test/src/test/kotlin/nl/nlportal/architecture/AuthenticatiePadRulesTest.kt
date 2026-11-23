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
import com.tngtech.archunit.lang.ArchRule
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** De regels van het ene authenticatiepad op de echte code. */
class AuthenticatiePadRulesTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("nl.nlportal")

    @Test
    fun `there is no resource server`() {
        AuthenticatiePadRules.production.geenResourceServer().check(classes)
    }

    @Test
    fun `there is no jwt decoder`() {
        AuthenticatiePadRules.production.geenJwtDecoder().check(classes)
    }

    @Test
    fun `there is no token exchange`() {
        AuthenticatiePadRules.production.geenTokenExchange().check(classes)
    }
}

/** Bewijst dat elke regel een overtreding vangt en een nette BFF-klasse laat staan (fixtures staan alleen in testcode). */
class AuthenticatiePadRulesProofTest {
    private val base = "nl.nlportal.architecture.fixtures"
    private val rules = AuthenticatiePadRules()

    private fun violating() = ClassFileImporter().importPackages("$base.authvio")

    private fun clean() = ClassFileImporter().importPackages("$base.authclean")

    private fun assertFailsOn(
        rule: ArchRule,
        className: String,
    ) {
        assertThatThrownBy { rule.check(violating()) }.isInstanceOf(AssertionError::class.java).hasMessageContaining(className)
    }

    @Test
    fun `the resource server rule fails on a resource server configuration`() {
        assertFailsOn(rules.geenResourceServer(), "ConfiguresResourceServer")
    }

    @Test
    fun `the decoder rule fails on a JwtDecoder`() {
        assertFailsOn(rules.geenJwtDecoder(), "HoldsJwtDecoder")
    }

    @Test
    fun `the token exchange rule fails on a token exchange type`() {
        assertFailsOn(rules.geenTokenExchange(), "DoesTokenExchange")
    }

    @Test
    fun `a class that uses oauth2Login passes the resource server rule`() {
        rules.geenResourceServer().allowEmptyShould(true).check(clean())
    }
}