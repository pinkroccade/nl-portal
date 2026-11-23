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
package nl.nlportal.verificatie.autoconfigure

import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.verificatie.service.VerificatieService
import nl.nlportal.verificatie.tenant.VerificatieConfiguratie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

internal class VerificatieAutoConfigurationTest {
    private val basis =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VerificatieAutoConfiguration::class.java))
            .withBean(KoppelingRegister::class.java, { TestTenants.register() })
            .withBean(GeheimenBron::class.java, { TestTenants.ALLE_GEHEIMEN })

    private val metModule =
        TestTenants.tenant(
            koppelingen = mapOf("verificatie" to TestTenants.zgwKoppeling("https://verificatie.example", clientId = "nl-portal", geheimnaam = "verificatie-secret")),
            modules = mapOf("verificatie" to VerificatieConfiguratie()),
        )
    private val contextRunner = basis.withBean(TenantDirectory::class.java, { TestTenants.directory(metModule) })

    @Test
    fun `de service is beschikbaar als de module aan staat`() {
        contextRunner
            .withPropertyValues("nl-portal.config.verificatie.enabled=true")
            .run { context -> assertThat(context).hasSingleBean(VerificatieService::class.java) }
    }

    @Test
    fun `er is niets beschikbaar als de module uit staat`() {
        contextRunner.run { context -> assertThat(context).doesNotHaveBean(VerificatieService::class.java) }
    }

    @Test
    fun `een eigen service gaat voor`() {
        val eigen = VerificatieService(TestTenants.register(), TestTenants.directory(), TestTenants.ALLE_GEHEIMEN)
        contextRunner
            .withPropertyValues("nl-portal.config.verificatie.enabled=true")
            .withBean(VerificatieService::class.java, { eigen })
            .run { context -> assertThat(context.getBean(VerificatieService::class.java)).isSameAs(eigen) }
    }

    @Test
    fun `de oude properties zijn vervallen, in yml-schrijfwijze en als omgevingsvariabele, en weigeren het opstarten`() {
        contextRunner
            .withPropertyValues("nl-portal.config.verificatie.enabled=true")
            .run { context ->
                val vervallen = context.getBean(VervallenProperties::class.java)
                assertThat(vervallen).isEqualTo(VervallenProperties("verificatie", "nl-portal.config.verificatie.properties"))
                listOf(
                    mapOf("nl-portal.config.verificatie.properties.url" to "http://oud"),
                    mapOf("nl-portal.config.verificatie.properties.types-need-verification" to "email"),
                    mapOf("nl-portal.config.verificatie.properties.ssl.enabled" to "false"),
                    mapOf("NL_PORTAL_CONFIG_VERIFICATIE_PROPERTIES_APIKEY" to "x"),
                    mapOf("NLPORTAL_CONFIG_VERIFICATIE_PROPERTIES_TEMPLATEIDEMAIL" to "x"),
                    mapOf("NLPORTAL_CONFIG_VERIFICATIE_PROPERTIES_SSL_TRUSTEDCERTIFICATE" to "x"),
                ).forEach { oud ->
                    val omgeving = StandardEnvironment().apply { propertySources.addFirst(bron(oud)) }
                    val fout = assertThrows<IllegalStateException>(oud.keys.first()) { vervallen.controleer(omgeving) }
                    assertThat(fout.message).contains("modules.verificatie").doesNotContain("http://oud")
                }
                // De schakelaar zelf blijft geldig.
                val schakelaar = StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("nieuw", mapOf("nl-portal.config.verificatie.enabled" to "true"))) }
                vervallen.controleer(schakelaar)
            }
    }

    @Test
    fun `aan zonder een tenant met modules verificatie weigert het opstarten, met een melding die naar het tenantbestand wijst`() {
        basis
            .withBean(TenantDirectory::class.java, { TestTenants.directory(TestTenants.tenant()) })
            .withPropertyValues("nl-portal.config.verificatie.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                val melding = generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString()
                assertThat(melding).contains("modules.verificatie", "nl-portal.config.verificatie.enabled")
            }
    }

    @Test
    fun `een tenantmap zonder tenants weigert het opstarten ook als de module aan staat`() {
        basis
            .withBean(TenantDirectory::class.java, { TestTenants.directory() })
            .withPropertyValues("nl-portal.config.verificatie.enabled=true")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `uit zonder een tenant met modules verificatie start gewoon`() {
        basis.withBean(TenantDirectory::class.java, { TestTenants.directory(TestTenants.tenant()) }).run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `een tenant met koppeling verificatie zonder modules verificatie weigert het opstarten, met de OIN in de melding`() {
        val koppeling = TestTenants.zgwKoppeling("https://verificatie.example", clientId = "nl-portal", geheimnaam = "verificatie-secret", oin = TestTenants.WIJDE_VELD)
        val zonderModule = TestTenants.tenant(TestTenants.WIJDE_VELD, koppelingen = mapOf("verificatie" to koppeling))
        basis
            .withBean(TenantDirectory::class.java, { TestTenants.directory(metModule, zonderModule) })
            .withPropertyValues("nl-portal.config.verificatie.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                val melding = generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString()
                assertThat(melding).contains(TestTenants.WIJDE_VELD.value, "koppelingen.verificatie", "modules.verificatie")
            }
    }

    @Test
    fun `een tenant zonder koppeling en zonder modules verificatie naast een ingerichte tenant start gewoon`() {
        val zonder = TestTenants.tenant(TestTenants.WIJDE_VELD)
        basis
            .withBean(TenantDirectory::class.java, { TestTenants.directory(metModule, zonder) })
            .withPropertyValues("nl-portal.config.verificatie.enabled=true")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    // Een omgevingsvariabele (hoofdletters met underscores) wordt als systeemomgeving gelezen, zoals de runtime dat doet.
    private fun bron(waarden: Map<String, String>) =
        if (waarden.keys.all { it == it.uppercase() }) {
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, waarden.toMutableMap<String, Any>())
        } else {
            MapPropertySource("oud", waarden)
        }
}