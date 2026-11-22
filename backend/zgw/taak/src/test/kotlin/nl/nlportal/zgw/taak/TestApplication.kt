/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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

import nl.nlportal.core.security.OauthSecurityAutoConfiguration
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.documentenapi.configuratie.DocumentApiConfiguratie
import nl.nlportal.documentenapi.configuratie.DocumentenApiConfiguratie
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@SpringBootApplication(
    exclude = [
        OauthSecurityAutoConfiguration::class,
    ],
)
class TestApplication {
    fun main(args: Array<String>) {
        runApplication<TestApplication>(*args)
    }

    @Bean
    fun springSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .authorizeExchange {
                it.anyExchange().permitAll()
            }.build()

    /** De tenant van de tests: de Documenten API staat als koppeling en als module in het tenantbestand. */
    @Bean
    fun tenantDirectory(): TenantDirectory =
        TestTenants.directory(
            TestTenants.tenant(
                koppelingen =
                    mapOf(
                        "documenten-api" to
                            TestTenants.koppeling(
                                "http://localhost:8001",
                                auth = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "test-client", secret = TestTenants.geheim("documenten-secret")),
                            ),
                    ),
                modules =
                    mapOf(
                        "documenten-api" to
                            DocumentenApiConfiguratie(
                                standaardDocumentApi = "documenten-api",
                                documentApis = mapOf("documenten-api" to DocumentApiConfiguratie(rsin = "051845623", documentTypeUrl = "http://localhost/catalogi/api/v1/informatieobjecttypen/1")),
                            ),
                    ),
            ),
        )

    @Bean
    fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN

    @Bean
    fun vasteTenantWebFilter() = VasteTenantWebFilter()
}