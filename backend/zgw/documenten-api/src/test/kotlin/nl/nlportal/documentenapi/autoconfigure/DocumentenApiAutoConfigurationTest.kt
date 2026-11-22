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
package nl.nlportal.documentenapi.autoconfigure

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.ZgwJwtGenerator
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.documentenapi.client.DocumentenApiClient
import nl.nlportal.documentenapi.service.DocumentenApiService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.web.reactive.function.client.WebClient

class DocumentenApiAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DocumentenApiAutoConfiguration::class.java))
            .withBean(KoppelingRegister::class.java, { TestTenants.register() })
            .withBean(TenantDirectory::class.java, { TestTenants.directory() })

    @Test
    fun `de beans bestaan als de module aanstaat en lezen de tenantgegevens uit register en directory`() {
        runner.withPropertyValues("nl-portal.config.documentenapis.enabled=true").run { context ->
            assertThat(context).hasSingleBean(DocumentenApiService::class.java).hasSingleBean(DocumentenApiClient::class.java)
        }
    }

    @Test
    fun `zonder de schakelaar bestaat de module niet`() {
        runner.run { context -> assertThat(context).doesNotHaveBean(DocumentenApiService::class.java).doesNotHaveBean(DocumentenApiClient::class.java) }
    }

    @Test
    fun `een eigen service vervangt de standaard`() {
        val eigen = DocumentenApiService(DocumentenApiClient(TestTenants.register()), TestTenants.directory())
        runner.withPropertyValues("nl-portal.config.documentenapis.enabled=true").withBean(DocumentenApiService::class.java, { eigen }).run { context ->
            assertThat(context.getBean(DocumentenApiService::class.java)).isSameAs(eigen)
        }
    }

    // --- vervallen properties: de oude `documentenapis.properties` weigeren het opstarten ---

    private fun metKoppelingen(omgevingsvariabelen: Map<String, Any> = emptyMap()) =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoppelingAutoConfiguration::class.java, DocumentenApiAutoConfiguration::class.java))
            .withBean(WebClient.Builder::class.java, { WebClient.builder() })
            .withBean(ZgwJwtGenerator::class.java, { ZgwJwtGenerator() })
            .withBean(TenantDirectory::class.java, { TestTenants.directory() })
            .withBean(GeheimenBron::class.java, { TestTenants.ALLE_GEHEIMEN })
            .withInitializer {
                it.environment.propertySources.addFirst(SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, omgevingsvariabelen))
            }.withPropertyValues("nl-portal.config.documentenapis.enabled=true")

    private fun startupMelding(context: AssertableApplicationContext) = generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString()

    @Test
    fun `een oude property uit de yml weigert het opstarten, met prefix en modules documenten-api maar zonder de waarde`() {
        metKoppelingen().withPropertyValues("nl-portal.config.documentenapis.properties.default-document-api=zeer-geheim").run { context ->
            assertThat(context).hasFailed()
            assertThat(startupMelding(context)).contains("nl-portal.config.documentenapis.properties", "documenten-api").doesNotContain("zeer-geheim")
        }
    }

    @Test
    fun `een oude property als omgevingsvariabele weigert het opstarten ook`() {
        metKoppelingen(mapOf("NLPORTAL_CONFIG_DOCUMENTENAPIS_PROPERTIES_STATUSWHITELIST" to "zeer-geheim")).run { context ->
            assertThat(context).hasFailed()
            assertThat(startupMelding(context)).contains("documenten-api").doesNotContain("zeer-geheim")
        }
    }

    @Test
    fun `alleen de schakelaar enabled start zonder fout`() {
        metKoppelingen().run { context -> assertThat(context).hasNotFailed() }
    }
}