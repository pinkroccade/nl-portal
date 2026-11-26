/*
 * Copyright 2015-2024 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.berichten.autoconfigure

import nl.nlportal.berichten.BERICHTEN_MODULE
import nl.nlportal.berichten.configuratie.controleerBerichtenHebbenObjectenKoppeling
import nl.nlportal.berichten.graphql.BerichtenQuery
import nl.nlportal.berichten.security.config.BerichtDocumentResourceHttpSecurityConfigurer
import nl.nlportal.berichten.service.BerichtenService
import nl.nlportal.berichten.web.rest.BerichtDocumentResource
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.core.security.config.HttpSecurityConfigurer
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnProperty(prefix = "nl-portal.config", name = ["documentenapis.enabled"], havingValue = "true")
class BerichtenAutoConfiguration {
    /** Lets the frontend module list and the module status know the module flag. */
    @Bean
    fun berichtenModuleFlag(): ModuleFlag = BERICHTEN_MODULE

    /** De oude `...berichten.properties.*` (objecttype-URL) is vervallen: staat die nog gezet, dan weigert core het opstarten. */
    @Bean
    fun berichtenVervallenProperties() = VervallenProperties("berichten", "nl-portal.config.berichten.properties", vervanging = "modules.berichten")

    /** Een tenant met `modules.berichten` zonder `koppelingen.objectenapi` weigert het opstarten, in plaats van pas bij de eerste inwoner te falen. */
    @Bean
    fun berichtenKoppelingControle(directory: TenantDirectory) = InitializingBean { controleerBerichtenHebbenObjectenKoppeling(directory) }

    @Bean
    fun berichtenService(
        objectenApiService: ObjectenApiService,
        documentenApiService: DocumentenApiService,
        directory: TenantDirectory,
    ): BerichtenService =
        BerichtenService(
            objectenApiService = objectenApiService,
            directory = directory,
            documentenApiService = documentenApiService,
        )

    @Bean
    @ConditionalOnMissingBean(BerichtenQuery::class)
    fun berichtenQuery(berichtenService: BerichtenService): BerichtenQuery = BerichtenQuery(berichtenService)

    @Bean
    @ConditionalOnMissingBean(BerichtDocumentResource::class)
    fun berichtDocumentResource(berichtenService: BerichtenService): BerichtDocumentResource = BerichtDocumentResource(berichtenService)

    @Bean
    @ConditionalOnMissingBean(BerichtDocumentResourceHttpSecurityConfigurer::class)
    fun berichtDocumentResourceHttpSecurityConfigurer(): HttpSecurityConfigurer = BerichtDocumentResourceHttpSecurityConfigurer()
}