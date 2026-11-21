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
package nl.nlportal.zaken

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.zakenapi.client.ZakenApiConfiguratie
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** De uitgesloten zaaktypen die voorheen in de testconfiguratie (`zaak-types-ids-excluded`) stonden. */
val UITGESLOTEN_ZAAKTYPEN = listOf("c2d3e114-bbe4-478d-80de-e8a8c8f8a948", "4d69cf3c-68b3-43f5-881e-d4984b7649cb")

/** Tenant Bosrand met koppelingen voor Zaken API en Catalogi API (beide naar [url]) en modulegegevens voor Zaken API naar [url]; elk request draait als deze tenant. */
fun zakenApiTestTenant(url: String) =
    TestTenants.tenant(
        koppelingen =
            mapOf(
                "zakenapi" to TestTenants.zgwKoppeling(url.trimEnd('/'), "valtimo_client", "zaken-secret"),
                "catalogiapi" to TestTenants.zgwKoppeling(url.trimEnd('/'), "valtimo_client", "catalogi-secret"),
            ),
        modules = mapOf("zakenapi" to ZakenApiConfiguratie(uitgeslotenZaaktypen = UITGESLOTEN_ZAAKTYPEN)),
    )

/** Een IT zonder tenantresolutie: [url] is pas bij runtime bekend, dus de tenant wordt hier in een bean gebouwd. */
open class ZakenTestTenantConfiguratie(
    private val url: () -> String,
) {
    @Bean
    open fun tenantDirectory(): TenantDirectory = TenantDirectory(listOf(zakenApiTestTenant(url())))

    @Bean
    open fun vasteTenantWebFilter() = VasteTenantWebFilter()

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN
}