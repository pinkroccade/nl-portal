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
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.documentenapi.configuratie.DocumentApiConfiguratie
import nl.nlportal.documentenapi.configuratie.DocumentenApiConfiguratie
import nl.nlportal.zakenapi.client.ZakenApiConfiguratie
import nl.nlportal.zgw.taak.tenant.TaakConfiguratie
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
                    koppelingen =
                        mapOf(
                            "zakenapi" to TestTenants.zgwKoppeling(url, "valtimo_client", "zaken-secret"),
                            "catalogiapi" to TestTenants.zgwKoppeling(url, "valtimo_client", "catalogi-secret"),
                            "objectenapi" to TestTenants.koppeling(url),
                            "documenten-api" to
                                TestTenants.koppeling(
                                    url,
                                    auth = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "test-client", secret = TestTenants.geheim("documenten-secret")),
                                ),
                        ),
                    modules =
                        mapOf(
                            "zakenapi" to ZakenApiConfiguratie(),
                            "taak" to TaakConfiguratie("$url/api/v2/objecttypes/00000000-0000-0000-0000-000000000001"),
                            "documenten-api" to
                                DocumentenApiConfiguratie(
                                    standaardDocumentApi = "documenten-api",
                                    documentApis = mapOf("documenten-api" to DocumentApiConfiguratie(rsin = "051845623", documentTypeUrl = "http://localhost/catalogi/api/v1/informatieobjecttypen/1")),
                                ),
                        ),
                ),
            ),
        )

    @Bean
    open fun vasteTenantWebFilter() = VasteTenantWebFilter()

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN
}