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
package nl.nlportal.haalcentraalbrp.autoconfiguration

import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.haalcentraalbrp.BRP_MODULE
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.haalcentraalbrp.tenant.BrpTenantConfiguratie
import nl.nlportal.haalcentraalbrp.service.HaalCentraalBrpService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * De BRP-module staat aan met `nl-portal.config.haalcentraal-brp.enabled=true` (de opstartkeuze, verdwijnt met #162). Per tenant en per request
 * beslist de centrale gate op de permission-flag `permission.haalcentraal-brp.enabled` en de gegevens in het tenantbestand
 * (`modules.brp` en `koppelingen.brp`).
 */
@AutoConfiguration(after = [KoppelingAutoConfiguration::class])
@ConditionalOnProperty(prefix = "nl-portal.config.haalcentraal-brp", name = ["enabled"], havingValue = "true")
class HaalCentraalBrpAutoConfiguration {
    /** Laat de gate, de frontend-modulelijst en de modulematrix de permission-flag van de module kennen. */
    @Bean
    fun brpModuleFlag(): ModuleFlag = BRP_MODULE

    @Bean
    @ConditionalOnMissingBean(HaalCentraalBrpService::class)
    fun haalCentraalBrpService(
        koppelingRegister: KoppelingRegister,
        tenantDirectory: TenantDirectory,
    ): HaalCentraalBrpService = HaalCentraalBrpService(koppelingRegister, tenantDirectory)

    /** De oude `...properties.*` (apiKey, ssl, brpFields, ...) zijn vervallen: staan ze nog gezet, dan weigert core het opstarten. */
    @Bean
    fun haalCentraalBrpVervallenProperties() = VervallenProperties(BrpTenantConfiguratie.MODULE, "nl-portal.config.haalcentraal-brp.properties")
}