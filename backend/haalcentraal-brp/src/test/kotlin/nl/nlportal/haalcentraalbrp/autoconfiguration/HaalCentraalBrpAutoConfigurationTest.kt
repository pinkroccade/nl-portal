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
package nl.nlportal.haalcentraalbrp.autoconfiguration

import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.haalcentraalbrp.BRP_MODULE
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.haalcentraalbrp.service.HaalCentraalBrpService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import nl.nlportal.core.tenancy.VervallenProperties
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

internal class HaalCentraalBrpAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HaalCentraalBrpAutoConfiguration::class.java))
            .withUserConfiguration(TenancyConfiguration::class.java)

    @Test
    fun `de service is beschikbaar als de module aan staat`() {
        contextRunner
            .withPropertyValues("nl-portal.config.haalcentraal-brp.enabled=true")
            .run { context -> assertThat(context).hasSingleBean(HaalCentraalBrpService::class.java) }
    }

    @Test
    fun `de oude properties van de module zijn vervallen en weigeren het opstarten, met een verwijzing naar modules brp`() {
        contextRunner
            .withPropertyValues("nl-portal.config.haalcentraal-brp.enabled=true")
            .run { context ->
                val vervallen = context.getBean(VervallenProperties::class.java)
                assertThat(vervallen).isEqualTo(VervallenProperties("brp", "nl-portal.config.haalcentraal-brp.properties"))
                val oudeInstelling = StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("oud", mapOf("nl-portal.config.haalcentraal-brp.properties.api-key" to "x"))) }
                assertThrows<IllegalStateException> { vervallen.controleer(oudeInstelling) }.also { assertThat(it.message).contains("modules.brp") }
            }
    }

    @Test
    fun `de module maakt zijn permission-flag bekend bij de gate`() {
        contextRunner
            .withPropertyValues("nl-portal.config.haalcentraal-brp.enabled=true")
            .run { context ->
                assertThat(context.getBean(ModuleFlag::class.java)).isSameAs(BRP_MODULE)
                assertThat(BRP_MODULE.flag.key).isEqualTo("permission.haalcentraal-brp.enabled")
                assertThat(BRP_MODULE.koppelingen).containsExactly("brp")
                assertThat(BRP_MODULE.moduleConfiguratie).isEqualTo("brp")
            }
    }

    @Test
    fun `er is niets beschikbaar als de module uit staat`() {
        contextRunner.run { context -> assertThat(context).doesNotHaveBean(HaalCentraalBrpService::class.java) }
    }

    @Test
    fun `een eigen service gaat voor`() {
        contextRunner
            .withPropertyValues("nl-portal.config.haalcentraal-brp.enabled=true")
            .withBean(HaalCentraalBrpService::class.java, { eigen })
            .run { context -> assertThat(context.getBean(HaalCentraalBrpService::class.java)).isSameAs(eigen) }
    }

    private val eigen = HaalCentraalBrpService(TestTenants.register(), TestTenants.directory())

    @Configuration
    internal class TenancyConfiguration {
        @Bean
        fun tenantDirectory() = TestTenants.directory()

        @Bean
        fun koppelingRegister(): KoppelingRegister = TestTenants.register()
    }
}