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
package nl.nlportal.testing

import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantWebFilter
import nl.nlportal.core.tenancy.TrustedProxies
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/** Waar de test zonder de tenancy van de applicatie draait (geen `nl-portal.tenancy.directory`) en geen eigen tenants meebrengt (`nl-portal.test.eigen-tenants`). */
class GeenTenancy : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ) = !context.environment.containsProperty("nl-portal.tenancy.directory") && !context.environment.containsProperty("nl-portal.test.eigen-tenants")
}

/** Eén vaste tenant (Bosrand, met een Objecten API-koppeling) voor elk request, voor tests van core zonder tenantresolutie. */
@Configuration(proxyBeanMethods = false)
@Conditional(GeenTenancy::class)
open class VasteTenantConfiguratie {
    @Bean
    open fun tenantDirectory(): TenantDirectory = TestTenants.directory(TestTenants.tenant(koppelingen = mapOf("objectenapi" to TestTenants.koppeling("http://bron.example"))))

    @Bean
    open fun vasteTenantWebFilter() = VasteTenantWebFilter()

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN
}

/**
 * Twee tenants op hun eigen host (`bosrand.example` en `wijdeveld.example`) met de echte tenantresolutie, voor tests van de gate met
 * twee tenants. Zet `nl-portal.test.eigen-tenants=true` en importeer deze configuratie.
 */
@Configuration(proxyBeanMethods = false)
open class TweeTenantsConfiguratie {
    @Bean
    open fun tenantDirectory(): TenantDirectory =
        TestTenants.directory(
            TestTenants.tenant(TestTenants.BOSRAND, domeinen = listOf(BOSRAND_HOST)),
            TestTenants.tenant(TestTenants.WIJDE_VELD, domeinen = listOf(WIJDE_VELD_HOST)),
        )

    @Bean
    open fun tenantWebFilter(directory: TenantDirectory) = TenantWebFilter(directory, TrustedProxies(emptyList()))

    @Bean
    open fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN

    companion object {
        const val BOSRAND_HOST = "bosrand.example"
        const val WIJDE_VELD_HOST = "wijdeveld.example"
    }
}