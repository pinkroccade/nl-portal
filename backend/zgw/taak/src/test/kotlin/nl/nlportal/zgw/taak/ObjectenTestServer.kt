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
package nl.nlportal.zgw.taak

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.documentenapi.configuratie.DocumentApiConfiguratie
import nl.nlportal.documentenapi.configuratie.DocumentenApiConfiguratie
import nl.nlportal.zgw.taak.tenant.TaakConfiguratie
import okhttp3.mockwebserver.MockWebServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** De nepserver die de Objecten API van de testtenant is; één per JVM, de ITs zetten er per test hun eigen dispatcher op. */
object ObjectenTestServer {
    val server: MockWebServer by lazy { MockWebServer().also { it.start() } }
}

/** De ITs van deze module draaien zonder tenantresolutie als tenant Bosrand, met een Objecten-koppeling naar [ObjectenTestServer] en de Documenten API als koppeling en module. */
@Configuration
open class ObjectenTestTenantConfiguratie {
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
                        "documenten-api" to
                            TestTenants.koppeling(
                                "http://localhost:8001",
                                auth = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "test-client", secret = TestTenants.geheim("documenten-secret")),
                            ),
                    ),
                modules =
                    mapOf(
                        "taak" to TaakConfiguratie("http://localhost:8010/api/v1/objecttypes/3c24cab6-4346-4c7d-912b-e34a1e9e21bf"),
                        "documenten-api" to
                            DocumentenApiConfiguratie(
                                standaardDocumentApi = "documenten-api",
                                documentApis = mapOf("documenten-api" to DocumentApiConfiguratie(rsin = "051845623", documentTypeUrl = "http://localhost/catalogi/api/v1/informatieobjecttypen/1")),
                            ),
                    ),
            ),
        )

    @Bean
    open fun vasteTenantWebFilter() = VasteTenantWebFilter()

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN
}