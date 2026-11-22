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
package nl.nlportal.zakenapi.autoconfigure

import nl.nlportal.catalogiapi.client.CatalogiApiClient
import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.modulestatus.ModuleStatusRegistry
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zakenapi.client.ZakenApiConfiguratie
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import nl.nlportal.core.koppeling.ZgwJwtGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.web.reactive.function.client.WebClient

/** De opstartregels van Zaken API: de vervallen properties weigeren het opstarten, een koppeling zonder modulegegevens ook. */
class ZakenApiAutoConfigurationTest {
    private val bosrand = TestTenants.BOSRAND
    private val koppeling = TestTenants.zgwKoppeling("http://localhost:8001", "nl-portal", "zaken-secret")

    private fun runner(
        directory: TenantDirectory = TestTenants.directory(TestTenants.tenant(koppelingen = mapOf("zakenapi" to koppeling), modules = mapOf("zakenapi" to ZakenApiConfiguratie()))),
        omgevingsvariabelen: Map<String, Any> = emptyMap(),
    ) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KoppelingAutoConfiguration::class.java, ZakenApiAutoConfiguration::class.java))
        .withBean(WebClient.Builder::class.java, { WebClient.builder() })
        .withBean(ZgwJwtGenerator::class.java, { ZgwJwtGenerator() })
        .withBean(TenantDirectory::class.java, { directory })
        .withBean(GeheimenBron::class.java, { TestTenants.ALLE_GEHEIMEN })
        .withBean(ModuleStatusRegistry::class.java, { ModuleStatusRegistry() })
        .withBean(CatalogiApiClient::class.java, { mock() })
        .withBean(DocumentenApiService::class.java, { mock() })
        .withBean(ObjectenApiService::class.java, { mock() })
        .withBean(AuthenticationMachtigingsDienstService::class.java, { mock() })
        .withInitializer { it.environment.propertySources.addFirst(SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, omgevingsvariabelen)) }
        .withPropertyValues("nl-portal.config.objectenapi.enabled=true", "nl-portal.config.catalogiapi.enabled=true", "nl-portal.config.zakenapi.enabled=true")

    private fun melding(context: AssertableApplicationContext) = generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString()

    @Test
    fun `de module start met alleen enabled`() {
        runner().run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `een oude property in yml-vorm weigert het opstarten en verwijst naar modules zakenapi, zonder de waarde te noemen`() {
        runner().withPropertyValues("nl-portal.config.zakenapi.properties.zaak-types-ids-excluded=zeer-geheim").run { context ->
            assertThat(context).hasFailed()
            assertThat(melding(context)).contains("nl-portal.config.zakenapi.properties", "modules.zakenapi").doesNotContain("zeer-geheim")
        }
    }

    @Test
    fun `de oude omgevingsvariabelen voor uitgesloten zaaktypen en de kvk-query weigeren het opstarten`() {
        listOf("NLPORTAL_CONFIG_ZAKENAPI_PROPERTIES_ZAAKTYPESIDSEXCLUDED", "NLPORTAL_CONFIG_ZAKENAPI_PROPERTIES_USENNPKVKQUERYIDENTIFICATORS").forEach { sleutel ->
            runner(omgevingsvariabelen = mapOf(sleutel to "zeer-geheim")).run { context ->
                assertThat(context).describedAs(sleutel).hasFailed()
                assertThat(melding(context)).contains("modules.zakenapi").doesNotContain("zeer-geheim")
            }
        }
    }

    @Test
    fun `een tenant met een koppeling zonder modulegegevens weigert het opstarten met de OIN`() {
        val zonderModulegegevens = TestTenants.directory(TestTenants.tenant(koppelingen = mapOf("zakenapi" to koppeling)))

        runner(zonderModulegegevens).run { context ->
            assertThat(context).hasFailed()
            assertThat(melding(context)).contains(bosrand.value, "modules.zakenapi")
        }
    }
}