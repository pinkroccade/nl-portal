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
import com.tngtech.archunit.lang.ArchRule
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Bewijst dat elke HTTP-clientregel een overtreding vangt en een nette module laat staan (fixtures staan alleen in testcode). */
class HttpClientRulesProofTest {
    private val base = "nl.nlportal.architecture.fixtures"
    private val rules = HttpClientRules(koppelingPackages = arrayOf("$base.httpclean.koppeling.."))

    private fun violating() = ClassFileImporter().importPackages("$base.httpvio")

    private fun clean() = ClassFileImporter().importPackages("$base.httpclean")

    private fun assertFailsOn(
        rule: ArchRule,
        vararg classNames: String,
    ) {
        val failure = assertThatThrownBy { rule.check(violating()) }.isInstanceOf(AssertionError::class.java)
        classNames.forEach { failure.hasMessageContaining(it) }
    }

    @Test
    fun `the own WebClient rule fails on create and builder`() {
        assertFailsOn(rules.noOwnWebClient(), "BuildsWebClient", "BuildsWebClientWithBuilder")
    }

    @Test
    fun `the WebClient type rule fails when a module holds a WebClient or a netty client`() {
        assertFailsOn(rules.noWebClientInModules(), "HoldsAWebClient", "BuildsWebClient", "Wiretaps")
    }

    @Test
    fun `the wiretap rule fails when a module turns wiretap on`() {
        assertFailsOn(rules.noWiretap(), "Wiretaps")
    }

    @Test
    fun `the request detail rule fails when a module logs request details`() {
        assertFailsOn(rules.noRequestDetailLogging(), "LogsRequestDetails")
    }

    @Test
    fun `a module that is listed as not yet migrated is exempt from every rule`() {
        val ratchet = HttpClientRules(koppelingPackages = arrayOf("$base.httpclean.koppeling.."), notYetMigrated = arrayOf("$base.httpvio.."))
        val everything = ClassFileImporter().importPackages("$base.httpvio", "$base.httpclean")

        ratchet.noOwnWebClient().check(everything)
        ratchet.noWebClientInModules().check(everything)
        ratchet.noWiretap().check(everything)
        ratchet.noRequestDetailLogging().check(everything)
    }

    @Test
    fun `the koppeling package and a module without a client pass every rule`() {
        val clean = clean()

        rules.noOwnWebClient().check(clean)
        rules.noWebClientInModules().check(clean)
        rules.noWiretap().check(clean)
        rules.noRequestDetailLogging().check(clean)
    }
}