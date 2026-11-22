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
package nl.nlportal.verificatie.autoconfigure

import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.verificatie.service.VerificatieService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * De verificatiemodule staat aan met `nl-portal.config.verificatie.enabled=true`. De gegevens per tenant staan in het tenantbestand
 * (`modules.verificatie` en `koppelingen.verificatie`); deze schakelaar is alleen de keuze of de runtime de module aanbiedt.
 */
@AutoConfiguration(after = [KoppelingAutoConfiguration::class])
@ConditionalOnProperty(prefix = "nl-portal.config", name = ["verificatie.enabled"], havingValue = "true")
class VerificatieAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(VerificatieService::class)
    fun verificatieService(
        koppelingRegister: KoppelingRegister,
        tenantDirectory: TenantDirectory,
        geheimenBron: GeheimenBron,
    ) = VerificatieService(koppelingRegister, tenantDirectory, geheimenBron)
}
