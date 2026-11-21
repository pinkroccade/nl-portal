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

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.OmgevingGeheimenBron
import nl.nlportal.core.tenancy.TenancyAutoConfiguration
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantModules
import nl.nlportal.core.tenancy.VervallenProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.env.Environment
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
        geheimenBron: ObjectProvider<GeheimenBron>,
        modules: ObjectProvider<TenantModules>,
        vervallen: ObjectProvider<VervallenProperties>,
        environment: Environment,
    ): KoppelingRegister {
        vervallen.orderedStream().forEach { it.controleer(environment) }
        // Tussenfase: een nog niet gemigreerde module leest `nl-portal.koppelingen`. Dat zijn dezelfde koppelingen en
        // credentials voor elke tenant, dus er mag maar één tenant zijn zolang dat blok gebruikt wordt.
        check(koppelingen.koppelingen.isEmpty() || directory.tenants.size <= 1) {
            "Meerdere tenants en toch koppelingen uit 'nl-portal.koppelingen': dan delen ze bron en credentials; zet de koppelingen in het tenantbestand"
        }
        // Een module die haar configuratie in het tenantbestand bijdraagt is gemigreerd: haar oude regel mag er niet meer staan, ook niet
        // als het tenantbestand de koppeling (nog) mist, want dan bediende de module stil de gedeelde, lenient opgebouwde koppeling.
        val gemigreerd =
            modules
                .getIfAvailable { TenantModules.GEEN }
                .configuraties
                .map { it.naam }
                .toSet()
        check(
            koppelingen.koppelingen.keys
                .intersect(gemigreerd)
                .isEmpty(),
        ) {
            "'nl-portal.koppelingen.${koppelingen.koppelingen.keys.intersect(gemigreerd).first()}' staat er nog, maar die module leest haar koppeling uit het tenantbestand; verwijder de regel"
        }
        return KoppelingRegister.bouwUitTenants(
            directory.tenants,
            geheimenBron.getIfAvailable { OmgevingGeheimenBron(environment) },
            tussenfase = directory.tenants.associate { it.oin to koppelingen.koppelingen },
            webClientBuilder = webClientBuilder,
            resilience = resilience,
            zgwJwtGenerator = zgwJwtGenerator,
        )
    }
}