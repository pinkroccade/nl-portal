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
package nl.nlportal.catalogiapi.autoconfigure

import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.catalogiapi.client.CatalogiApiClient
import nl.nlportal.catalogiapi.service.CatalogiApiService
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

internal class CatalogiApiAutoConfigurationTest {
    private val basis =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CatalogiApiAutoConfiguration::class.java))
            .withUserConfiguration(TenancyConfiguration::class.java)

    private val metKoppeling =
        basis.withBean(TenantDirectory::class.java, { TestTenants.directory(TestTenants.tenant(koppelingen = mapOf("catalogiapi" to TestTenants.koppeling("http://catalogi.example")))) })
    private val zonderKoppeling = basis.withBean(TenantDirectory::class.java, { TestTenants.directory(TestTenants.tenant()) })

    @Test
    fun `client en service zijn beschikbaar als de module aan staat`() {
        metKoppeling.withPropertyValues("nl-portal.config.catalogiapi.enabled=true").run { context ->
            assertThat(context).hasSingleBean(CatalogiApiClient::class.java)
            assertThat(context).hasSingleBean(CatalogiApiService::class.java)
        }
    }

    @Test
    fun `er is niets beschikbaar als de module uit staat`() {
        metKoppeling.run { context ->
            assertThat(context).doesNotHaveBean(CatalogiApiClient::class.java)
            assertThat(context).doesNotHaveBean(CatalogiApiService::class.java)
        }
    }

    @Test
    fun `de oude properties zijn vervallen, in yml-schrijfwijze en als omgevingsvariabele`() {
        metKoppeling.withPropertyValues("nl-portal.config.catalogiapi.enabled=true").run { context ->
            val vervallen = context.getBean(VervallenProperties::class.java)
            assertThat(vervallen).isEqualTo(VervallenProperties("catalogiapi", "nl-portal.config.catalogiapi.properties", "koppelingen.catalogiapi"))
            listOf(
                mapOf("nl-portal.config.catalogiapi.properties.url" to "http://oud"),
                mapOf("nl-portal.config.catalogiapi.properties.secret" to "x"),
                mapOf("NLPORTAL_CONFIG_CATALOGIAPI_PROPERTIES_URL" to "http://oud"),
                mapOf("NLPORTAL_CONFIG_CATALOGIAPI_PROPERTIES_SECRET" to "x"),
            ).forEach { oud ->
                val omgeving = StandardEnvironment().apply { propertySources.addFirst(bron(oud)) }
                val fout = assertThrows<IllegalStateException>(oud.keys.first()) { vervallen.controleer(omgeving) }
                assertThat(fout.message).contains("koppelingen.catalogiapi").doesNotContain("modules.catalogiapi", "http://oud")
            }
            val schakelaar = StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("nieuw", mapOf("nl-portal.config.catalogiapi.enabled" to "true"))) }
            vervallen.controleer(schakelaar)
        }
    }

    @Test
    fun `aan zonder een tenant met de koppeling catalogiapi weigert het opstarten`() {
        zonderKoppeling.withPropertyValues("nl-portal.config.catalogiapi.enabled=true").run { context ->
            assertThat(context).hasFailed()
            val melding = generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString()
            assertThat(melding).contains("koppelingen.catalogiapi", "nl-portal.config.catalogiapi.enabled")
        }
    }

    @Test
    fun `een tenantmap zonder tenants weigert het opstarten ook als de module aan staat`() {
        basis
            .withBean(TenantDirectory::class.java, { TestTenants.directory() })
            .withPropertyValues("nl-portal.config.catalogiapi.enabled=true")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `uit zonder een tenant met de koppeling catalogiapi start gewoon`() {
        zonderKoppeling.run { context -> assertThat(context).hasNotFailed() }
    }

    private fun bron(waarden: Map<String, String>) =
        if (waarden.keys.all { it == it.uppercase() }) {
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, waarden.toMutableMap<String, Any>())
        } else {
            MapPropertySource("oud", waarden)
        }

    @Configuration
    internal class TenancyConfiguration {
        @Bean
        fun koppelingRegister(): KoppelingRegister = TestTenants.register()
    }
}