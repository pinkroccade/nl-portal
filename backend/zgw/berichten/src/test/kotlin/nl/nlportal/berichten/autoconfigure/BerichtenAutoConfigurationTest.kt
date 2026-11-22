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
package nl.nlportal.berichten.autoconfigure

import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

internal class BerichtenAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BerichtenAutoConfiguration::class.java))
            .withBean(ObjectenApiService::class.java, { mock<ObjectenApiService>() })
            .withBean(DocumentenApiService::class.java, { mock<DocumentenApiService>() })
            .withBean(TenantDirectory::class.java, { mock<TenantDirectory>() })
            .withBean(ModuleGate::class.java, { mock<ModuleGate>() })
            .withPropertyValues("nl-portal.config.documentenapis.enabled=true")

    @Test
    fun `de oude properties zijn vervallen, in yml-schrijfwijze en als omgevingsvariabele`() {
        runner.run { context ->
            val vervallen = context.getBean(VervallenProperties::class.java)
            assertThat(vervallen.module).isEqualTo("berichten")
            listOf(
                mapOf("nl-portal.config.berichten.properties.bericht-object-type-url" to "http://oud"),
                mapOf("NLPORTAL_CONFIG_BERICHTEN_PROPERTIES_BERICHTOBJECTTYPEURL" to "http://oud"),
            ).forEach { oud ->
                val bron =
                    if (oud.keys.all { it == it.uppercase() }) {
                        SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, oud.toMutableMap<String, Any>())
                    } else {
                        MapPropertySource("oud", oud)
                    }
                val omgeving = StandardEnvironment().apply { propertySources.addFirst(bron) }
                val fout = assertThrows<IllegalStateException>(oud.keys.first()) { vervallen.controleer(omgeving) }
                assertThat(fout.message).contains("modules.berichten").doesNotContain("http://oud")
            }
        }
    }
}