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
package nl.nlportal.berichten

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import okhttp3.mockwebserver.MockWebServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** De nepserver die de Objecten API (en de documenten-API) van de testtenant is; één per JVM, de ITs zetten er per test hun eigen dispatcher op. */
object ObjectenTestServer {
    val server: MockWebServer by lazy { MockWebServer().also { it.start() } }
}

/** De ITs van deze module draaien zonder tenantresolutie als tenant Bosrand; de Objecten API wijst naar [ObjectenTestServer]. */
@Configuration
open class BerichtenTestTenantConfiguratie {
    @Bean
    open fun tenantDirectory(): TenantDirectory =
        TestTenants.directory(
            TestTenants.tenant(
                koppelingen =
                    mapOf(
                        "objectenapi" to
                            TestTenants.koppeling(
                                ObjectenTestServer.server
                                    .url("/")
                                    .toString()
                                    .trimEnd('/'),
                            ),
                    ),
            ),
        )

    @Bean
    open fun vasteTenantWebFilter() = VasteTenantWebFilter()

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN
}