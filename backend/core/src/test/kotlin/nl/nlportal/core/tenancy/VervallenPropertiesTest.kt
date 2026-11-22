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
package nl.nlportal.core.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.env.MockEnvironment

/** Seam: de data class en `controleer`: meldt oude instellingen onder een prefix, in elke schrijfwijze, en noemt nooit een waarde. */
class VervallenPropertiesTest {
    private val prefix = "nl-portal.config.haalcentraal-brp.properties"
    private val vervallen = VervallenProperties("brp", prefix)

    @Test
    fun `module, prefix en standaardvervanging hebben de opgegeven waarden`() {
        assertThat(vervallen.module).isEqualTo("brp")
        assertThat(vervallen.prefix).isEqualTo(prefix)
        assertThat(vervallen.vervanging).isEqualTo("modules.brp en koppelingen")
    }

    @Test
    fun `een eigen vervanging overschrijft de standaard`() {
        val eigen = VervallenProperties("kvk", "oud.kvk", "de kvk-koppeling")

        assertThat(eigen.vervanging).isEqualTo("de kvk-koppeling")
        assertThat(eigen.module).isEqualTo("kvk")
        assertThat(eigen.prefix).isEqualTo("oud.kvk")
    }

    @Test
    fun `zonder oude instellingen slaagt de controle`() {
        val environment = MockEnvironment().withProperty("nl-portal.config.andere.sleutel", "x")

        vervallen.controleer(environment)
    }

    @Test
    fun `een oude property onder de prefix weigert met prefix module en vervanging maar zonder waarde`() {
        val environment = MockEnvironment().withProperty("$prefix.geheim-sleutel", "supergeheim")

        val fout = assertThrows<IllegalStateException> { vervallen.controleer(environment) }

        assertThat(fout.message).contains("'$prefix'", "module 'brp'", "(modules.brp en koppelingen)")
        assertThat(fout.message).doesNotContain("supergeheim")
    }

    @Test
    fun `omgevingsvariabele-schrijfwijze telt als gezet`() {
        val environment = MockEnvironment()
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource("systemEnvironment", mapOf("NLPORTAL_CONFIG_HAALCENTRAALBRP_PROPERTIES_BSNSLEUTEL" to "x")),
        )

        assertThrows<IllegalStateException> { vervallen.controleer(environment) }
    }

    @Test
    fun `een sleutel die alleen met dezelfde letters begint telt niet als gezet`() {
        val environment = MockEnvironment().withProperty("$prefix-extra.sleutel", "x")

        vervallen.controleer(environment)
    }

    @Test
    fun `een sleutel waar de prefix een deel van is telt niet als gezet`() {
        val environment = MockEnvironment().withProperty("voor.$prefix.sleutel", "x")

        vervallen.controleer(environment)
    }
}