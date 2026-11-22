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
package nl.nlportal.zakenapi.autoconfigure

import nl.nlportal.core.modulestatus.ModuleStatusRegistry
import nl.nlportal.catalogiapi.client.CatalogiApiClient
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zakenapi.client.ZakenApiClient
import nl.nlportal.zakenapi.client.controleerKoppelingenHebbenModulegegevens
import nl.nlportal.zakenapi.service.ZakenApiService
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnProperty(prefix = "nl-portal.config", name = ["objectenapi.enabled", "catalogiapi.enabled", "zakenapi.enabled"], havingValue = "true")
class ZakenApiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ZakenApiService::class)
    fun zakenApiService(
        zakenApiClient: ZakenApiClient,
        documentenApiService: DocumentenApiService,
        objectenApiService: ObjectenApiService,
        authenticationMachtigingsDienstService: AuthenticationMachtigingsDienstService,
    ): ZakenApiService =
        ZakenApiService(
            zakenApiClient = zakenApiClient,
            documentenApiService = documentenApiService,
            objectenApiService = objectenApiService,
            authenticationMachtigingsDienstService = authenticationMachtigingsDienstService,
        )

    @Bean
    fun zakenApiClient(
        koppelingRegister: KoppelingRegister,
        tenantDirectory: TenantDirectory,
        catalogiApiClient: CatalogiApiClient,
        moduleStatus: ModuleStatusRegistry,
    ): ZakenApiClient =
        ZakenApiClient(
            koppelingRegister,
            tenantDirectory,
            catalogiApiClient,
            moduleStatus,
        )

    /** Een tenant met `koppelingen.zakenapi` zonder `modules.zakenapi` weigert het opstarten, in plaats van pas bij de eerste inwoner te falen. */
    @Bean
    fun zakenApiModulegegevensControle(tenantDirectory: TenantDirectory) = InitializingBean { controleerKoppelingenHebbenModulegegevens(tenantDirectory) }

    /** De oude `nl-portal.config.zakenapi.properties.*` (url, clientId, secret, zaakTypesIdsExcluded, useNnpKvkQueryIdentificators) zijn vervallen. */
    @Bean
    fun zakenApiVervallenProperties() = VervallenProperties(ZakenApiClient.MODULE, "nl-portal.config.zakenapi.properties")
}