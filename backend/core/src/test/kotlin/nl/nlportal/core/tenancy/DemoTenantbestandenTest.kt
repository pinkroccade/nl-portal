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
package nl.nlportal.core.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Seam: de validator over de tenantbestanden die de repo meelevert (de CI-stap "validator over de demo-tenantbestanden"). */
class DemoTenantbestandenTest {
    @Test
    fun `de demo-tenantbestanden van docker-compose voldoen aan de validator`() {
        val result = TenantFileLoader().load(Path.of("../../docker-compose/imports/tenants"))

        assertThat(result.violations).isEmpty()
        assertThat(result.tenants).hasSize(1)
    }

    @Test
    fun `de demo-tenant laat het testinlogmiddel toe, dat altijd niveau basis levert`() {
        val demo = TenantFileLoader().load(Path.of("../../docker-compose/imports/tenants")).tenants.single()

        assertThat(demo.inlogmiddelen).containsExactly(Inlogmiddel.TEST)
        assertThat(demo.minimumNiveau)
            .describedAs("het testinlogmiddel zet acr=Basis; een hoger minimum weigert elke demo-login")
            .isEqualTo(Betrouwbaarheidsniveau.BASIS)
    }

    @Test
    fun `de tenantbestanden van de app-tests voldoen aan de validator`() {
        val result = TenantFileLoader().load(Path.of("../app/src/test/resources/tenants"))

        assertThat(result.violations).isEmpty()
    }
}