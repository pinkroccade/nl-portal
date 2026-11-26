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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Bewijst dat elke gateregel het foute patroon vangt en een nette module laat staan. */
class ModuleGateRegelsProofTest {
    private val base = "nl.nlportal.architecture.fixtures"
    private val regels = ModuleGateRegels(overgang = setOf("zaken"))

    @Test
    fun `a controller without @ModuleEndpoint fails`() {
        val klassen = ClassFileImporter().importPackages("$base.gatevio.zonder")

        assertThatThrownBy { regels.everyEndpointIsRegistered().check(klassen) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("ZonderRegistratieQuery")
    }

    @Test
    fun `a registered endpoint that calls the gate itself fails`() {
        val klassen = ClassFileImporter().importPackages("$base.gatevio.zelfgate")

        assertThatThrownBy { regels.registeredEndpointsDoNotCallTheGate().check(klassen) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("ZelfGateQuery")
    }

    @Test
    fun `a module outside the transition list cannot register as transitional`() {
        val klassen = ClassFileImporter().importPackages("$base.gatevio.overgang")

        assertThatThrownBy { regels.onlyListedModulesMayBeTransitional().check(klassen) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("OnbekendeOvergangQuery")
    }

    @Test
    fun `registered endpoints, the core and a listed transitional module pass`() {
        val klassen = ClassFileImporter().importPackages("$base.gateclean")

        regels.everyEndpointIsRegistered().check(klassen)
        regels.registeredEndpointsDoNotCallTheGate().check(klassen)
        regels.onlyListedModulesMayBeTransitional().check(klassen)
    }
}