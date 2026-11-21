/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.core.koppeling

import nl.nlportal.core.tenancy.TenancyAutoConfiguration
import nl.nlportal.core.tenancy.TenantDirectory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient

/**
 * Registreert de koppelingen en het register waarmee modules er een [KoppelingClient] van krijgen. Het register bestaat
 * zodra de tenancy aanstaat. Tussenfase (epic #499, stap 2 tot en met 6): elke tenant krijgt de koppelingen uit
 * `nl-portal.koppelingen`; er is dan één tenant. De koppelingen per tenant uit het tenantbestand komen met de migratie van de modules.
 */
@AutoConfiguration(after = [TenancyAutoConfiguration::class])
@EnableConfigurationProperties(KoppelingenProperties::class, KoppelingResilienceInstellingen::class)
class KoppelingAutoConfiguration {
    @Bean
    @ConditionalOnBean(TenantDirectory::class)
    fun koppelingRegister(
        webClientBuilder: WebClient.Builder,
        zgwJwtGenerator: ZgwJwtGenerator,
        directory: TenantDirectory,
        koppelingen: KoppelingenProperties,
        resilience: KoppelingResilienceInstellingen,
    ): KoppelingRegister {
        // Zolang alle tenants dezelfde koppelingen en credentials krijgen, mag er maar één tenant zijn.
        check(koppelingen.koppelingen.isEmpty() || directory.tenants.size <= 1) {
            "Meerdere tenants en toch koppelingen uit 'nl-portal.koppelingen': dan delen ze bron en credentials; configureer koppelingen per tenant"
        }
        return KoppelingRegister.bouw(
            directory.tenants.associate { it.oin to koppelingen.koppelingen },
            webClientBuilder,
            resilience,
            zgwJwtGenerator,
        )
    }
}