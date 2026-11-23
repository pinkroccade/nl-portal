/*
 * Copyright 2026 PinkRoccade Local Government B.V.
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
package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.tenancy.TenancyAutoConfiguration
import nl.nlportal.core.tenancy.TenantDirectory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/** Marker voor de opstartcontrole [BffVereistAutoConfiguration]; heeft geen gedrag. */
class BffVereist

/**
 * De [BffAutoConfiguration] staat alleen aan met tenancy. Een applicatie die de login nodig heeft (`:app`, `:app-dev`) zet
 * `nl-portal.authentication.bff.vereist=true`; ontbreekt dan de tenancy, dan weigert het opstarten in plaats van dat de app
 * zonder login draait.
 */
@AutoConfiguration(after = [TenancyAutoConfiguration::class])
@ConditionalOnMissingBean(TenantDirectory::class)
@ConditionalOnProperty(prefix = "nl-portal.authentication.bff", name = ["vereist"], havingValue = "true")
class BffVereistAutoConfiguration {
    @Bean
    fun bffVereist(): BffVereist = throw IllegalStateException("De login (BFF) is verplicht maar de tenancy staat uit: zet nl-portal.tenancy.directory op de map met de tenantbestanden")
}