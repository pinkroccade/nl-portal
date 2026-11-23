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
package nl.nlportal.zgw.taak.autoconfigure

import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.form.service.ObjectsApiFormDefinitionService
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.taak.service.TaakService
import nl.nlportal.zgw.taak.tenant.TaakConfiguratie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

internal class TaakAutoConfigurationTest {
    private val objectenKoppeling = mapOf("objectenapi" to TestTenants.koppeling("http://objecten.example"))
    private val taakModule = mapOf("taak" to TaakConfiguratie("http://objecten.example/api/v2/objecttypes/1"))

    private fun runner(directory: TenantDirectory) =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaakAutoConfiguration::class.java))
            .withBean(TenantDirectory::class.java, { directory })
            .withBean(ObjectsApiClient::class.java, { mock<ObjectsApiClient>() })
            .withBean(AuthenticationMachtigingsDienstService::class.java, { mock<AuthenticationMachtigingsDienstService>() })
            .withBean(ObjectsApiFormDefinitionService::class.java, { mock<ObjectsApiFormDefinitionService>() })
            .withPropertyValues("nl-portal.config.objectenapi.enabled=true", "nl-portal.config.taak.enabled=true")

    private val volledig = runner(TestTenants.directory(TestTenants.tenant(koppelingen = objectenKoppeling, modules = taakModule)))

    @Test
    fun `de taakservice is beschikbaar als de module aan staat en een tenant modules taak en de koppeling heeft`() {
        volledig.run { context -> assertThat(context).hasSingleBean(TaakService::class.java) }
    }

    @Test
    fun `de oude properties zijn vervallen, in yml-schrijfwijze en als omgevingsvariabele, en weigeren het opstarten`() {
        volledig.run { context ->
            val vervallen = context.getBean(VervallenProperties::class.java)
            assertThat(vervallen).isEqualTo(VervallenProperties("taak", "nl-portal.config.taak.properties", "modules.taak"))
            listOf(
                mapOf("nl-portal.config.taak.properties.object-type-url" to "http://oud"),
                mapOf("nl-portal.config.taak.properties.objectTypeUrl" to "http://oud"),
                mapOf("NLPORTAL_CONFIG_TAAK_PROPERTIES_OBJECTTYPEURL" to "http://oud"),
                mapOf("NL_PORTAL_CONFIG_TAAK_PROPERTIES_TYPEURL" to "http://oud"),
            ).forEach { oud ->
                val omgeving = StandardEnvironment().apply { propertySources.addFirst(bron(oud)) }
                val fout = assertThrows<IllegalStateException>(oud.keys.first()) { vervallen.controleer(omgeving) }
                assertThat(fout.message).contains("modules.taak").doesNotContain("http://oud")
            }
            // De schakelaar zelf blijft geldig.
            val schakelaar = StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("nieuw", mapOf("nl-portal.config.taak.enabled" to "true"))) }
            vervallen.controleer(schakelaar)
        }
    }

    @Test
    fun `aan zonder een tenant met modules taak weigert het opstarten met een melding naar het tenantbestand`() {
        runner(TestTenants.directory(TestTenants.tenant(koppelingen = objectenKoppeling))).run { context ->
            assertThat(context).hasFailed()
            assertThat(melding(context.startupFailure)).contains("modules.taak", "nl-portal.config.taak.enabled")
        }
    }

    @Test
    fun `een tenant met modules taak maar zonder koppeling objectenapi weigert het opstarten met het OIN, zonder waarde`() {
        val oin = Oin("00000099000000000002")
        runner(TestTenants.directory(TestTenants.tenant(oin = oin, modules = taakModule))).run { context ->
            assertThat(context).hasFailed()
            assertThat(melding(context.startupFailure)).contains("00000099000000000002", "koppelingen.objectenapi").doesNotContain("objecttypes")
        }
    }

    @Test
    fun `uit zonder modules taak start gewoon`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaakAutoConfiguration::class.java))
            .run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(TaakService::class.java) }
    }

    private fun melding(fout: Throwable?) = generateSequence(fout) { it.cause }.map { it.message }.joinToString()

    // Een omgevingsvariabele (hoofdletters met underscores) wordt als systeemomgeving gelezen, zoals de runtime dat doet.
    private fun bron(waarden: Map<String, String>) =
        if (waarden.keys.all { it == it.uppercase() }) {
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, waarden.toMutableMap<String, Any>())
        } else {
            MapPropertySource("oud", waarden)
        }
}