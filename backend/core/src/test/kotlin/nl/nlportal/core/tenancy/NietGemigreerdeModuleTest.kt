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

import nl.nlportal.core.testing.TestTenants
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory

/** Een niet-gemigreerde module (besluiten, openklant, openproduct) kan niet aan staan in een runtime met meer dan één tenant (T22, #521). */
class NietGemigreerdeModuleTest {
    private val besluiten = NietGemigreerdeModule("besluiten")
    private val openklant = NietGemigreerdeModule("openklant")

    @Test
    fun `with one tenant a module that is not migrated may be on`() {
        assertThatCode { NietGemigreerdeModule.controleer(1, listOf(besluiten, openklant)) }.doesNotThrowAnyException()
    }

    @Test
    fun `with more than one tenant and no such module the runtime starts`() {
        assertThatCode { NietGemigreerdeModule.controleer(2, emptyList()) }.doesNotThrowAnyException()
    }

    @Test
    fun `with more than one tenant a module that is not migrated refuses the startup and the message names only the modules`() {
        assertThatThrownBy { NietGemigreerdeModule.controleer(2, listOf(openklant, besluiten)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'besluiten', 'openklant'")
            .hasMessageContaining("2 tenants")
    }

    @Test
    fun `the startup check of the application refuses two tenants with a module that is not migrated on`() {
        val twee = TestTenants.directory(TestTenants.tenant(TestTenants.BOSRAND), TestTenants.tenant(TestTenants.WIJDE_VELD))
        val controle = { modules: List<NietGemigreerdeModule> ->
            val beans = StaticListableBeanFactory(modules.associateBy { it.module })
            TenancyAutoConfiguration().nietGemigreerdeModulesControle(twee, beans.getBeanProvider(NietGemigreerdeModule::class.java))
        }

        assertThatThrownBy { controle(listOf(besluiten)).afterPropertiesSet() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'besluiten'")
        assertThatCode { controle(emptyList()).afterPropertiesSet() }.doesNotThrowAnyException()
    }
}