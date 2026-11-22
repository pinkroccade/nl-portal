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
package nl.nlportal.berichten.configuratie

import org.assertj.core.api.Assertions.assertThat
import nl.nlportal.core.testing.TestTenants
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BerichtenTenantConfiguratieTest {
    private val configuratie = BerichtenTenantConfiguratie()

    @Test
    fun `een ingevulde objecttype-URL is geldig`() {
        assertThat(configuratie.valideer(BerichtenConfiguratie("http://objecten.example/objecttypes/1"))).isEmpty()
    }

    @Test
    fun `een lege of blanco objecttype-URL is een overtreding`() {
        listOf("", "  ").forEach { url ->
            assertThat(configuratie.valideer(BerichtenConfiguratie(url))).hasSize(1)
        }
    }

    @Test
    fun `de naam is berichten`() {
        assertThat(configuratie.naam).isEqualTo("berichten")
    }

    @Test
    fun `modules berichten zonder koppeling objectenapi weigert het opstarten met de OIN`() {
        val zonder = TestTenants.tenant(modules = mapOf("berichten" to BerichtenConfiguratie("http://o.example/1")))
        val fout = assertThrows<IllegalStateException> { controleerBerichtenHebbenObjectenKoppeling(TestTenants.directory(zonder)) }
        assertThat(fout.message).contains(zonder.oin.value, "koppelingen.objectenapi")
    }

    @Test
    fun `met koppeling objectenapi, of zonder modules berichten, start het op`() {
        val met =
            TestTenants.tenant(
                koppelingen = mapOf("objectenapi" to TestTenants.koppeling("http://objecten.example")),
                modules = mapOf("berichten" to BerichtenConfiguratie("http://o.example/1")),
            )
        controleerBerichtenHebbenObjectenKoppeling(TestTenants.directory(met, TestTenants.tenant(oin = TestTenants.WIJDE_VELD)))
    }
}