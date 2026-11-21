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
package nl.nlportal.payment.direct.autoconfiguration

import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.koppeling.ZgwJwtGenerator
import nl.nlportal.payment.direct.configuratie.DirectPaymentTenantConfiguratie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ByteArrayResource
import org.springframework.web.reactive.function.client.WebClient

internal class DirectPaymentAutoConfigurationTest {
    private fun contextRunner(
        yml: String? = null,
        omgevingsvariabelen: Map<String, Any> = emptyMap(),
    ) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KoppelingAutoConfiguration::class.java, DirectPaymentAutoConfiguration::class.java))
        .withBean(WebClient.Builder::class.java, { WebClient.builder() })
        .withBean(ZgwJwtGenerator::class.java, { ZgwJwtGenerator() })
        .withBean(TenantDirectory::class.java, { TenantDirectory(listOf(TestTenants.tenant(TestTenants.BOSRAND))) })
        .withBean(GeheimenBron::class.java, { TestTenants.ALLE_GEHEIMEN })
        .withInitializer {
            it.environment.propertySources.addFirst(SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, omgevingsvariabelen))
            yml?.let { tekst -> YamlPropertySourceLoader().load("oude-yml", ByteArrayResource(tekst.toByteArray())).forEach(it.environment.propertySources::addFirst) }
        }

    private fun melding(context: AssertableApplicationContext) = generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString()

    @Test
    fun `de oude properties van payment-direct zijn vervallen onder de hele prefix, ook enabled`() {
        contextRunner().run { context ->
            assertThat(context.getBean(VervallenProperties::class.java))
                .isEqualTo(VervallenProperties(DirectPaymentTenantConfiguratie.NAAM, "nl-portal.config.payment.direct"))
        }
    }

    @Test
    fun `zonder oude properties start de module gewoon`() {
        contextRunner().run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `een oude yml-sleutel weigert het opstarten, ook enabled, met een verwijzing naar modules payment-direct`() {
        contextRunner(yml = "nl-portal:\n  config:\n    payment:\n      direct:\n        enabled: true\n        properties:\n          psp-id: zeer-geheim\n").run { context ->
            assertThat(context).hasFailed()
            assertThat(melding(context)).contains("nl-portal.config.payment.direct", "modules.payment-direct").doesNotContain("zeer-geheim")
        }
    }

    @Test
    fun `een oude omgevingsvariabele weigert het opstarten`() {
        contextRunner(omgevingsvariabelen = mapOf("NLPORTAL_CONFIG_PAYMENT_DIRECT_ENABLED" to "true", "NLPORTAL_CONFIG_PAYMENT_DIRECT_PROPERTIES_PSPID" to "zeer-geheim")).run { context ->
            assertThat(context).hasFailed()
            assertThat(melding(context)).contains("modules.payment-direct").doesNotContain("zeer-geheim")
        }
    }
}