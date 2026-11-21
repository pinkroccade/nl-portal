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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.web.server.adapter.ForwardedHeaderTransformer
import org.springframework.core.type.AnnotatedTypeMetadata
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** Waar of de tenancy aanstaat: de eigenschap bestaat, met welke waarde dan ook (anders zou `false` haar stil uitzetten). */
internal class TenancyIngesteld : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ) = context.environment.containsProperty("nl-portal.tenancy.directory")
}

/**
 * Laadt en valideert de tenantbestanden bij opstarten (een fout weigert het opstarten), en zet de tenantresolutie
 * vóór de security-keten. Staat aan zodra `nl-portal.tenancy.directory` een waarde heeft, welke dan ook: `false` of een lege waarde zet de tenancy niet uit maar weigert het opstarten.
 */
@AutoConfiguration
@Conditional(TenancyIngesteld::class)
@EnableConfigurationProperties(TenancyProperties::class)
class TenancyAutoConfiguration {
    /** De bijdragen van de modules op het klassenpad (`ServiceLoader`); een eigen bean vervangt ze, bijvoorbeeld in een test. */
    @Bean
    @ConditionalOnMissingBean(TenantModules::class)
    fun tenantModules(): TenantModules = TenantModules.ontdek()

    @Bean
    fun tenantDirectory(
        properties: TenancyProperties,
        modules: TenantModules,
    ): TenantDirectory {
        require(!properties.directory.isNullOrBlank()) { "nl-portal.tenancy.directory is leeg; noem de map met de tenantbestanden" }
        val tenants = TenantFileLoader(modules = modules).load(Path.of(properties.directory!!)).orThrow()
        logger.info { "Tenants geladen: ${tenants.joinToString { "${it.oin} (${it.domeinen.joinToString()})" }}" }
        return TenantDirectory(tenants)
    }

    @Bean
    @ConditionalOnMissingBean(GeheimenBron::class)
    fun geheimenBron(environment: Environment): GeheimenBron = OmgevingGeheimenBron(environment)

    @Bean
    fun tenantWebFilter(
        directory: TenantDirectory,
        properties: TenancyProperties,
    ) = TenantWebFilter(directory, TrustedProxies(properties.trustedProxies))

    @Bean
    fun managementExposureGuard(environment: Environment) = ManagementExposureGuard(environment)

    @Bean
    fun forwardedHeadersGuard(
        environment: Environment,
        transformer: ObjectProvider<ForwardedHeaderTransformer>,
    ) = ForwardedHeadersGuard(environment) { transformer.getIfAvailable() != null }

    @Bean
    fun tenantObservationConvention() = TenantServerRequestObservationConvention()

    /** De bean bestaat voor het bijeffect: het registreren van de MDC-brug en de globale Reactor-hook voor context-propagatie. */
    @Bean
    fun registreerOinMdcBrug() = OinMdcBridge
}