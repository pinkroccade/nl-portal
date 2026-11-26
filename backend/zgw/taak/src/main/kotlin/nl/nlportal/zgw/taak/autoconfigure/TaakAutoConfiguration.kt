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
package nl.nlportal.zgw.taak.autoconfigure

import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.core.security.config.HttpSecurityConfigurer
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.documentenapi.service.VirusScanService
import nl.nlportal.form.service.ObjectsApiFormDefinitionService
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.taak.TAAK_MODULE
import nl.nlportal.zgw.taak.graphql.TaakFormQuery
import nl.nlportal.zgw.taak.graphql.TaakMutationV2
import nl.nlportal.zgw.taak.graphql.TaakQueryV2
import nl.nlportal.zgw.taak.security.config.TaakDocumentResourceHttpSecurityConfigurer
import nl.nlportal.zgw.taak.service.TaakService
import nl.nlportal.zgw.taak.tenant.TaakTenantConfiguratie
import nl.nlportal.zgw.taak.web.rest.TaakDocumentResource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnProperty(prefix = "nl-portal.config", name = ["objectenapi.enabled", "taak.enabled"], havingValue = "true")
class TaakAutoConfiguration {
    /** Laat de gate, de frontend-modulelijst en de modulematrix de permission-flag van de module kennen. */
    @Bean
    fun taakModuleFlag(): ModuleFlag = TAAK_MODULE

    /** Aan zonder tenant met `modules.taak` (en de koppeling `objectenapi`) weigert het opstarten. */
    @Bean
    fun taakConfiguratieControle(directory: TenantDirectory) = TaakConfiguratieControle(directory)

    /** De oude `...properties.*` (objecttype-URL) zijn vervallen: staan ze nog gezet, dan weigert core het opstarten. */
    @Bean
    fun taakVervallenProperties() = VervallenProperties(TaakTenantConfiguratie.NAAM, "nl-portal.config.taak.properties", vervanging = "modules.${TaakTenantConfiguratie.NAAM}")

    @Bean
    @ConditionalOnMissingBean(TaakService::class)
    fun taskService(
        objectsApiClient: ObjectsApiClient,
        tenantDirectory: TenantDirectory,
        authenticationMachtigingsService: AuthenticationMachtigingsDienstService,
    ): TaakService = TaakService(objectsApiClient, tenantDirectory, authenticationMachtigingsService)

    @Bean
    @ConditionalOnMissingBean(TaakQueryV2::class)
    fun taskQueryV2(taskService: TaakService): TaakQueryV2 = TaakQueryV2(taskService)

    @Bean
    @ConditionalOnMissingBean(TaakMutationV2::class)
    fun taskMutationV2(taskService: TaakService): TaakMutationV2 = TaakMutationV2(taskService)

    @Bean
    @ConditionalOnMissingBean(TaakFormQuery::class)
    fun taakFormQuery(
        taskService: TaakService,
        objectsApiFormDefinitionService: ObjectsApiFormDefinitionService,
    ): TaakFormQuery = TaakFormQuery(taskService, objectsApiFormDefinitionService)

    @Bean
    @ConditionalOnMissingBean(TaakDocumentResource::class)
    @ConditionalOnProperty(prefix = "nl-portal.config.documentenapis", name = ["enabled"], havingValue = "true")
    fun taakDocumentUploadResource(
        taakService: TaakService,
        documentenApiService: DocumentenApiService,
        virusScanService: VirusScanService?,
    ): TaakDocumentResource = TaakDocumentResource(taakService, documentenApiService, virusScanService)

    @Bean
    @ConditionalOnMissingBean(TaakDocumentResourceHttpSecurityConfigurer::class)
    @ConditionalOnProperty(prefix = "nl-portal.config.documentenapis", name = ["enabled"], havingValue = "true")
    fun taakDocumentResourceHttpSecurityConfigurer(): HttpSecurityConfigurer = TaakDocumentResourceHttpSecurityConfigurer()
}