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

import nl.nlportal.berichten.configuratie.BerichtenConfiguratie
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.documentenapi.configuratie.DocumentApiConfiguratie
import nl.nlportal.documentenapi.configuratie.DocumentenApiConfiguratie
import okhttp3.mockwebserver.MockWebServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** De nepserver die de Objecten API (en de documenten-API) van de testtenant is; één per JVM, de ITs zetten er per test hun eigen dispatcher op. */
object ObjectenTestServer {
    val server: MockWebServer by lazy { MockWebServer().also { it.start() } }
}

/** De ITs van deze module draaien zonder tenantresolutie als tenant Bosrand; de Objecten API en de Documenten API wijzen naar [ObjectenTestServer]. */
@Configuration
open class BerichtenTestTenantConfiguratie {
    @Bean
    open fun tenantDirectory(): TenantDirectory {
        val url =
            ObjectenTestServer.server
                .url("/")
                .toString()
                .trimEnd('/')
        return TestTenants.directory(
            TestTenants.tenant(
                koppelingen =
                    mapOf(
                        "objectenapi" to TestTenants.koppeling(url),
                        "documenten-api" to
                            TestTenants.koppeling(
                                url,
                                auth = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "test-client", secret = TestTenants.geheim("documenten-secret")),
                            ),
                    ),
                modules =
                    mapOf(
                        "berichten" to BerichtenConfiguratie("https://example.com/api/v1/objecttypes/78731088-430f-49fd-9a4c-80ddd42ded28"),
                        "documenten-api" to
                            DocumentenApiConfiguratie(
                                standaardDocumentApi = "documenten-api",
                                documentApis = mapOf("documenten-api" to DocumentApiConfiguratie(rsin = "051845623", documentTypeUrl = "http://localhost/catalogi/api/v1/informatieobjecttypen/1")),
                            ),
                    ),
            ),
        )
    }

    @Bean
    open fun vasteTenantWebFilter() = VasteTenantWebFilter()

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN
}