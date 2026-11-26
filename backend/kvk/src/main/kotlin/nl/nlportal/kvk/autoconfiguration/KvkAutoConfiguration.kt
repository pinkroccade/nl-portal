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
package nl.nlportal.kvk.autoconfiguration

import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.kvk.KVK_MODULE
import nl.nlportal.kvk.graphql.KvkQuery
import nl.nlportal.kvk.service.KvkService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * De KvK-module staat aan met `nl-portal.config.kvk.enabled=true` (de opstartkeuze, verdwijnt met #162). Per tenant en per request beslist
 * de centrale gate op de permission-flag `permission.kvk.enabled` en de koppeling `kvk` in het tenantbestand (url en sleutel).
 */
@AutoConfiguration(after = [KoppelingAutoConfiguration::class])
@ConditionalOnProperty(prefix = "nl-portal.config", name = ["kvk.enabled"], havingValue = "true")
class KvkAutoConfiguration {
    /** Laat de gate, de frontend-modulelijst en de modulematrix de permission-flag van de module kennen. */
    @Bean
    fun kvkModuleFlag(): ModuleFlag = KVK_MODULE

    @Bean
    @ConditionalOnMissingBean(KvkService::class)
    fun kvkService(koppelingRegister: KoppelingRegister): KvkService = KvkService(koppelingRegister)

    @Bean
    @ConditionalOnMissingBean(KvkQuery::class)
    fun kvkQuery(kvkService: KvkService): KvkQuery = KvkQuery(kvkService)

    /**
     * KvK heeft per gemeente alleen een koppeling, geen `modules.kvk`; daarom kan `ModuleContext` niet toetsen of de module een
     * koppeling heeft. Deze controle doet dat: aan zonder een tenant met `koppelingen.kvk` weigert het opstarten.
     */
    @Bean
    fun kvkKoppelingControle(directory: TenantDirectory) = KvkKoppelingControle(directory)

    /** De oude `...properties.*` (url, apiKey, ssl) zijn vervallen: staan ze nog gezet, dan weigert core het opstarten. */
    @Bean
    fun kvkVervallenProperties() = VervallenProperties(KvkService.MODULE, "nl-portal.config.kvk.properties", vervanging = "koppelingen.${KvkService.KOPPELING}")
}