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
package nl.nlportal.documentenapi.autoconfigure

import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.documentenapi.client.DocumentenApiClient
import nl.nlportal.documentenapi.service.DocumentenApiService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

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
}