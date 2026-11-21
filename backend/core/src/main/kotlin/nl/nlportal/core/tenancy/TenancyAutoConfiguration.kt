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
package nl.nlportal.core.tenancy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Laadt en valideert de tenantbestanden bij opstarten (een fout weigert het opstarten), en zet de tenantresolutie
 * vóór de security-keten. Staat aan zodra `nl-portal.tenancy.directory` is ingesteld.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "nl-portal.tenancy", name = ["directory"])
@EnableConfigurationProperties(TenancyProperties::class)
class TenancyAutoConfiguration {
    @Bean
    fun tenantDirectory(properties: TenancyProperties): TenantDirectory {
        val tenants = TenantFileLoader().load(Path.of(properties.directory!!)).orThrow()
        logger.info { "Tenants geladen: ${tenants.joinToString { "${it.oin} (${it.domeinen.joinToString()})" }}" }
        return TenantDirectory(tenants)
    }

    @Bean
    fun tenantWebFilter(
        directory: TenantDirectory,
        properties: TenancyProperties,
    ) = TenantWebFilter(directory, TrustedProxies(properties.trustedProxies))

    @Bean
    fun managementExposureGuard(environment: Environment) = ManagementExposureGuard(environment)

    @Bean
    fun tenantObservationConvention() = TenantServerRequestObservationConvention()

    @Bean
    fun oinMdcInitializer() = OinMdcBridge
}