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
package nl.nlportal.app

import nl.nlportal.core.tenancy.ForwardedHeadersGuard
import nl.nlportal.core.tenancy.ManagementExposureGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.io.FileSystemResource
import org.springframework.mock.env.MockEnvironment

/**
 * De application.yml die de app meelevert (niet die van de testconfiguratie, die hem overschrijft) voldoet aan de
 * opstartcontroles voor de actuator en de forwarded-headers, ook op Kubernetes.
 */
class ShippedConfigurationTest {
    private fun omgeving(cloudPlatform: String? = null): MockEnvironment {
        val yaml = YamlPropertiesFactoryBean().apply { setResources(FileSystemResource("src/main/resources/config/application.yml")) }.getObject()!!
        return MockEnvironment().also { env ->
            env.propertySources.addFirst(PropertiesPropertySource("shipped", yaml))
            cloudPlatform?.let { env.setProperty("spring.main.cloud-platform", it) }
            env.setProperty("MANAGEMENT_SERVER_PORT", "8091")
        }
    }

    @Test
    fun `de actuator staat op een eigen poort met alleen health en modulestatus`() {
        assertThat(ManagementExposureGuard(omgeving()).controleer()).isEmpty()
        assertThat(omgeving().getProperty("management.endpoints.web.exposure.include")).isEqualTo("health, modulestatus")
    }

    @Test
    fun `de forwarded-verwerking van Spring staat uit, ook op Kubernetes`() {
        assertThat(ForwardedHeadersGuard(omgeving("kubernetes")) { false }.controleer()).isEmpty()
        assertThat(omgeving().getProperty("server.forward-headers-strategy")).isEqualTo("none")
    }
}