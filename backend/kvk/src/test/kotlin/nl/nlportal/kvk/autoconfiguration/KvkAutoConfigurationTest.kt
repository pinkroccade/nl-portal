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
package nl.nlportal.kvk.autoconfiguration

import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.kvk.graphql.KvkQuery
import nl.nlportal.kvk.service.KvkService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

internal class KvkAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KvkAutoConfiguration::class.java))
            .withUserConfiguration(TenancyConfiguration::class.java)

    @Test
    fun `service en query zijn beschikbaar als de module aan staat`() {
        contextRunner
            .withPropertyValues("nl-portal.config.kvk.enabled=true")
            .run { context ->
                assertThat(context).hasSingleBean(KvkService::class.java)
                assertThat(context).hasSingleBean(KvkQuery::class.java)
            }
    }

    @Test
    fun `er is niets beschikbaar als de module uit staat`() {
        contextRunner.run { context ->
            assertThat(context).doesNotHaveBean(KvkService::class.java)
            assertThat(context).doesNotHaveBean(KvkQuery::class.java)
        }
    }

    @Test
    fun `een eigen service gaat voor`() {
        val eigen = KvkService(TestTenants.register())
        contextRunner
            .withPropertyValues("nl-portal.config.kvk.enabled=true")
            .withBean(KvkService::class.java, { eigen })
            .run { context -> assertThat(context.getBean(KvkService::class.java)).isSameAs(eigen) }
    }

    @Test
    fun `de oude properties zijn vervallen, in yml-schrijfwijze en als omgevingsvariabele, en weigeren het opstarten`() {
        contextRunner
            .withPropertyValues("nl-portal.config.kvk.enabled=true")
            .run { context ->
                val vervallen = context.getBean(VervallenProperties::class.java)
                assertThat(vervallen).isEqualTo(VervallenProperties("kvk", "nl-portal.config.kvk.properties"))
                listOf(
                    mapOf("nl-portal.config.kvk.properties.url" to "http://oud"),
                    mapOf("nl-portal.config.kvk.properties.apiKey" to "x"),
                    mapOf("nl-portal.config.kvk.properties.ssl.enabled" to "false"),
                    mapOf("NL_PORTAL_CONFIG_KVK_PROPERTIES_APIKEY" to "x"),
                    mapOf("NLPORTAL_CONFIG_KVK_PROPERTIES_SSL_TRUSTEDCERTIFICATE" to "x"),
                ).forEach { oud ->
                    val omgeving = StandardEnvironment().apply { propertySources.addFirst(bron(oud)) }
                    val fout = assertThrows<IllegalStateException>(oud.keys.first()) { vervallen.controleer(omgeving) }
                    assertThat(fout.message).contains("modules.kvk").doesNotContain("http://oud")
                }
                // De schakelaar zelf blijft geldig.
                val schakelaar = StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("nieuw", mapOf("nl-portal.config.kvk.enabled" to "true"))) }
                vervallen.controleer(schakelaar)
            }
    }

    // Een omgevingsvariabele (hoofdletters met underscores) wordt als systeemomgeving gelezen, zoals de runtime dat doet.
    private fun bron(waarden: Map<String, String>) = if (waarden.keys.all { it == it.uppercase() }) SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, waarden.toMutableMap<String, Any>()) else MapPropertySource("oud", waarden)

    @Configuration
    internal class TenancyConfiguration {
        @Bean
        fun koppelingRegister(): KoppelingRegister = TestTenants.register()
    }
}