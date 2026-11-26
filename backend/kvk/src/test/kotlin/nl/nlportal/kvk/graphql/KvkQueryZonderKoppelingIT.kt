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
package nl.nlportal.kvk.graphql

import nl.nlportal.commonground.authentication.WithBedrijfUser
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.graphql.test.tester.HttpGraphQlTester
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Foutpad via GraphQL: de gate weigert een tenant zonder `koppelingen.kvk` met `MODULE_NOT_AVAILABLE`, zonder OIN of koppelingnaam in de melding.
 * Een andere tenant heeft de koppeling wel, zodat de startcontrole van de module slaagt en het verzoek zelf de fout geeft.
 */
@SpringBootTest
@AutoConfigureHttpGraphQlTester
@AutoConfigureWebTestClient(timeout = "36000")
internal class KvkQueryZonderKoppelingIT(
    @Autowired private val httpGraphQlTester: HttpGraphQlTester,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun featureFlags(registry: DynamicPropertyRegistry) = KvkQueryIT.vlaggen.registreer(registry)
    }

    @TestConfiguration
    class TenantConfiguration {
        @Bean
        fun tenantDirectory(): TenantDirectory =
            TestTenants.directory(
                TestTenants.tenant(TestTenants.BOSRAND),
                TestTenants.tenant(TestTenants.WIJDE_VELD, koppelingen = mapOf("kvk" to TestTenants.koppeling("http://kvk.example", oin = TestTenants.WIJDE_VELD))),
            )

        @Bean
        fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN

        @Bean
        fun vasteTenantWebFilter() = VasteTenantWebFilter()
    }

    @Test
    @WithBedrijfUser(kvkNummer = "90012768")
    fun `een tenant zonder KvK-koppeling krijgt MODULE_NOT_AVAILABLE van de gate en geen stil null`() {
        httpGraphQlTester
            .document("query { getBedrijf { naam } }")
            .execute()
            .errors()
            .satisfy { fout ->
                assertThat(fout.single().extensions["code"]).isEqualTo("MODULE_NOT_AVAILABLE")
                assertThat(fout.single().message).doesNotContain(TestTenants.BOSRAND.value, "90012768")
            }
    }
}