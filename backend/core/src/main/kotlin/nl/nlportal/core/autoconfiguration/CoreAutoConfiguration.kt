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
package nl.nlportal.core.autoconfiguration

import nl.nlportal.core.koppeling.ZgwJwtGenerator
import nl.nlportal.core.featureflag.FeatureContextFactory
import nl.nlportal.core.featureflag.FeatureGate
import nl.nlportal.core.frontend.configuration.FrontendConfigurationProperties
import nl.nlportal.core.frontend.configuration.FrontendModuleConfigurationProperties
import nl.nlportal.core.frontend.service.FrontendConfigurationService
import nl.nlportal.core.frontend.web.rest.FrontendConfigurationResource
import nl.nlportal.core.frontend.web.rest.PingResource
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.util.Mapper
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.modulegate.ModuleEndpoints
import nl.nlportal.core.modulegate.ModuleGateWebFilter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

@AutoConfiguration
@EnableConfigurationProperties(
    FrontendModuleConfigurationProperties::class,
    FrontendConfigurationProperties::class,
)
class CoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ZgwJwtGenerator::class)
    fun zgwJwtGenerator(): ZgwJwtGenerator = ZgwJwtGenerator()

    @Bean("objectMapper")
    @ConditionalOnMissingBean(name = ["jsonMapper"])
    fun objectMapper(): JsonMapper = Mapper.get()

    @Bean
    @ConditionalOnMissingBean(ModuleGate::class)
    fun moduleGate(
        featureGate: FeatureGate,
        featureContextFactory: FeatureContextFactory,
        modules: ObjectProvider<ModuleFlag>,
        directory: ObjectProvider<TenantDirectory>,
    ) = ModuleGate(featureGate, featureContextFactory, modules.orderedStream().toList()) { directory.getObject() }

    /** The registration of the endpoints at the gate; a registration that names an unknown module stops the startup. */
    @Bean
    @ConditionalOnMissingBean(ModuleEndpoints::class)
    fun moduleEndpoints(
        beanFactory: ListableBeanFactory,
        modules: ObjectProvider<ModuleFlag>,
    ) = ModuleEndpoints(beanFactory, modules.orderedStream().toList())

    @Bean
    fun moduleEndpointsControle(endpoints: ModuleEndpoints) = SmartInitializingSingleton { endpoints.controleer() }

    /** Signals at startup a flag that is on without configuration of the tenant (ADR 0021, 0026): the module stays off. */
    @Bean
    fun moduleFlagZonderConfiguratieWaarschuwing(gate: ModuleGate) =
        SmartInitializingSingleton {
            runCatching { gate.flagZonderConfiguratie() }
                .onFailure {
                    io.github.oshai.kotlinlogging.KotlinLogging
                        .logger {}
                        .warn { "De modulematrix is bij het opstarten niet te bepalen: ${it::class.simpleName}" }
                }.getOrDefault(emptyList())
                .forEach {
                    io.github.oshai.kotlinlogging.KotlinLogging
                        .logger {}
                        .warn { "Tenant ${it.oin}: de flag van module '${it.module}' staat aan, maar de configuratie ontbreekt; de module staat uit" }
                }
        }

    /** The REST side of the central module gate: deny by default, module off means 503. */
    @Bean
    @ConditionalOnMissingBean(ModuleGateWebFilter::class)
    fun moduleGateWebFilter(
        handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
        endpoints: ModuleEndpoints,
        gate: ModuleGate,
    ) = ModuleGateWebFilter(handlerMappings, endpoints, gate)

    @Bean
    @ConditionalOnMissingBean(FrontendConfigurationService::class)
    fun frontendConfigurationService(
        frontendConfigurationProperties: FrontendConfigurationProperties,
        frontendModuleConfigurationProperties: FrontendModuleConfigurationProperties,
        featureGate: FeatureGate,
        moduleGate: ModuleGate,
    ) = FrontendConfigurationService(
        frontendConfigurationProperties = frontendConfigurationProperties,
        frontendModuleConfigurationProperties = frontendModuleConfigurationProperties,
        featureGate = featureGate,
        moduleGate = moduleGate,
    )

    @Bean
    @ConditionalOnMissingBean(FrontendConfigurationResource::class)
    fun frontendConfigurationResource(
        frontendConfigurationService: FrontendConfigurationService,
        featureContextFactory: FeatureContextFactory,
    ) = FrontendConfigurationResource(frontendConfigurationService, featureContextFactory)

    @Bean
    @ConditionalOnProperty(prefix = "nl-portal.support", name = ["ping-endpoint.enabled"], havingValue = "true")
    @ConditionalOnMissingBean(PingResource::class)
    fun pingResource(): PingResource = PingResource()
}