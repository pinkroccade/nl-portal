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
package nl.nlportal.zgw.objectenapi.autoconfiguration

import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [KoppelingAutoConfiguration::class])
@ConditionalOnProperty(prefix = "nl-portal.config.objectenapi", name = ["enabled"], havingValue = "true")
class ObjectsApiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObjectsApiClient::class)
    fun objectsApiClient(koppelingRegister: KoppelingRegister): ObjectsApiClient = ObjectsApiClient(koppelingRegister)

    @Bean
    @ConditionalOnMissingBean(ObjectenApiService::class)
    fun objectenApiService(objectsApiClient: ObjectsApiClient): ObjectenApiService = ObjectenApiService(objectsApiClient)

    /** Aan zonder een tenant met `koppelingen.objectenapi` weigert het opstarten. */
    @Bean
    fun objectenApiKoppelingControle(directory: TenantDirectory) = ObjectenApiKoppelingControle(directory)

    /** De oude `...properties.*` (url, token) zijn vervallen: staan ze nog gezet, dan weigert core het opstarten. */
    @Bean
    fun objectenApiVervallenProperties() = VervallenProperties(ObjectsApiClient.MODULE, "nl-portal.config.objectenapi.properties", vervanging = "koppelingen.${ObjectsApiClient.MODULE}")
}