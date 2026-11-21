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
package nl.nlportal.documentenapi.autoconfigure

import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.documentenapi.client.DocumentenApiClient
import nl.nlportal.documentenapi.configuratie.DocumentenApiTenantConfiguratie
import nl.nlportal.documentenapi.service.DocumentenApiService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * De Documenten API-module. De gegevens per tenant staan in het tenantbestand (`modules.documenten-api` en `koppelingen`); alleen de
 * schakelaar `nl-portal.config.documentenapis.enabled` bepaalt nog of de modulebeans bestaan (andere modules hangen ervan af).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "nl-portal.config.documentenapis", name = ["enabled"], havingValue = "true")
class DocumentenApiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentenApiService::class)
    fun documentenApiService(
        documentenApiClient: DocumentenApiClient,
        tenantDirectory: TenantDirectory,
    ): DocumentenApiService = DocumentenApiService(documentenApiClient, tenantDirectory)

    @Bean
    fun documentenApiClient(koppelingRegister: KoppelingRegister): DocumentenApiClient = DocumentenApiClient(koppelingRegister)

    /** De oude `documentenapis.properties.*` zijn vervallen: ze weigeren het opstarten, met een verwijzing naar `modules.documenten-api`. */
    @Bean
    fun documentenApiVervallenProperties() = VervallenProperties(DocumentenApiTenantConfiguratie.NAAM, "nl-portal.config.documentenapis.properties")
}
