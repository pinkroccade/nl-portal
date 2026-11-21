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
package nl.nlportal.openproduct

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.zakenapi.client.ZakenApiConfiguratie
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Alle ITs van deze module draaien zonder tenantresolutie als tenant Bosrand; de Zaken API van die tenant wijst naar de nepserver op poort 9000
 * (de ITs die de Zaken API aanroepen starten hun nepserver daar). Wordt door de componentscan van `TestApplication` opgepikt.
 */
@Configuration
open class OpenProductTestTenantConfiguratie {
    private val url = "http://localhost:9000"

    @Bean
    open fun tenantDirectory(): TenantDirectory =
        TenantDirectory(
            listOf(
                TestTenants.tenant(
                    koppelingen = mapOf("zakenapi" to TestTenants.zgwKoppeling(url, "valtimo_client", "zaken-secret")),
                    modules = mapOf("zakenapi" to ZakenApiConfiguratie()),
                ),
            ),
        )

    @Bean
    open fun vasteTenantWebFilter() = VasteTenantWebFilter()

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN
}