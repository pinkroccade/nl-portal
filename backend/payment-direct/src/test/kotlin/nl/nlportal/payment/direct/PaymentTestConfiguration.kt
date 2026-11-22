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
package nl.nlportal.payment.direct

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import okhttp3.mockwebserver.MockWebServer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/** De nepserver die de Objecten API van de testtenant is; één per JVM, de ITs zetten er per test hun eigen dispatcher op. */
object ObjectenTestServer {
    val server: MockWebServer by lazy { MockWebServer().also { it.start() } }
}

/**
 * Voor de Spring-ITs van de module, zonder tenantresolutie: een tenant met payment-direct, waarvan de koppeling naar de nep-provider
 * wijst (`test.payment.url`, pas bij runtime bekend), en een filter dat die tenant op elk request zet.
 */
@TestConfiguration
class PaymentTestConfiguration {
    @Bean
    fun tenantDirectory(environment: Environment): TenantDirectory {
        val tenant = PaymentTestTenants.tenant(TestTenants.BOSRAND, environment.getProperty("test.payment.url", "http://localhost:1").trimEnd('/'))
        // De Objecten API van de tenant: de nepserver van [ObjectenTestServer], één per JVM.
        return TestTenants.directory(
            tenant.copy(
                koppelingen =
                    tenant.koppelingen + (
                        "objectenapi" to
                            TestTenants.koppeling(
                                ObjectenTestServer.server
                                    .url("/")
                                    .toString()
                                    .trimEnd('/'),
                            )
                    ),
            ),
        )
    }

    @Bean
    fun geheimenBron(): GeheimenBron = PaymentTestTenants.geheimen(TestTenants.BOSRAND to PaymentTestTenants.WEBHOOK_GEHEIM)

    @Bean
    fun vasteTenantWebFilter() = VasteTenantWebFilter(TestTenants.BOSRAND)
}