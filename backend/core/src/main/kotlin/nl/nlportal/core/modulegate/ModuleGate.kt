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
package nl.nlportal.core.modulegate

import nl.nlportal.core.featureflag.FeatureContextFactory
import nl.nlportal.core.featureflag.FeatureGate
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDirectory

/** The verdict on one module for one tenant: the two conditions and their outcome. */
data class ModuleBeoordeling(
    val module: String,
    val oin: Oin,
    /** The tenant file holds the koppelingen and the module configuration of the module and of every module it requires. */
    val geconfigureerd: Boolean,
    /** The permission flag of the module and of every module it requires is on for the tenant. */
    val flag: Boolean,
) {
    /** Usable: flag and configuration (ADR 0021, 0026). */
    val bruikbaar get() = geconfigureerd && flag
}

/**
 * The central gate of the modules (ADR 0026). A module is usable for a tenant when its permission flag and the flags of the
 * modules it requires ([ModuleFlag.requires]) are on for that tenant and the tenant file holds the configuration of all of them.
 * The tenant comes from the [TenantContext], never from a parameter of the caller, and the flag is evaluated with the OIN in
 * the evaluation context; a missing tenant, a missing flag or a failing flag source means "not usable" (fail-closed).
 *
 * The GraphQL instrumentation and the REST filter call [controleer] for every request to a registered endpoint; resolvers do
 * not call the gate themselves. The configuration comes from the tenant register, which is fixed at startup, so a flag change
 * before the next restart can never make another tenant or a module without configuration reachable.
 */
class ModuleGate(
    private val featureGate: FeatureGate,
    private val featureContextFactory: FeatureContextFactory,
    private val modules: List<ModuleFlag>,
    private val tenantDirectory: () -> TenantDirectory,
) {
    constructor(
        featureGate: FeatureGate,
        featureContextFactory: FeatureContextFactory,
        modules: List<ModuleFlag>,
        directory: TenantDirectory,
    ) : this(featureGate, featureContextFactory, modules, { directory })

    /** Resolved on first use: the directory is part of the tenancy and a context without it only fails when the gate is asked. */
    private val directory: TenantDirectory by lazy(tenantDirectory)

    /** The verdict on [module] for the tenant [oin]; an unknown tenant is not configured and has no flag. */
    fun beoordeel(
        module: ModuleFlag,
        oin: Oin,
    ): ModuleBeoordeling {
        val tenant = directory.forOin(oin)
        val betrokken = listOf(module) + module.allRequired
        val context = featureContextFactory.create(oin)
        return ModuleBeoordeling(
            module = module.module,
            oin = oin,
            geconfigureerd = tenant != null && betrokken.all { it.isConfiguredFor(tenant) },
            flag = tenant != null && betrokken.all { featureGate.isEnabled(it.flag, context) },
        )
    }

    fun isEnabled(
        module: ModuleFlag,
        oin: Oin,
    ): Boolean = beoordeel(module, oin).bruikbaar

    /** Whether [module] is usable for the tenant of the current request. */
    suspend fun isEnabled(module: ModuleFlag): Boolean = isEnabled(module, TenantContext.current())

    /** Throws [ModuleNotAvailableException] when [module] is not usable for the tenant of the current request. */
    suspend fun require(module: ModuleFlag) {
        if (!isEnabled(module)) throw ModuleNotAvailableException(module.module)
    }

    /**
     * The decision for a request to an endpoint with [toewijzing]: no registration is denied, the core and modules that are not on
     * the gate yet are let through, a module needs to be usable for the tenant of the current request.
     */
    suspend fun controleer(
        toewijzing: EndpointToewijzing?,
        endpoint: String,
    ) {
        when (toewijzing) {
            null -> throw EndpointNietGeregistreerdException(endpoint)
            EndpointToewijzing.Kern, is EndpointToewijzing.Overgang -> Unit
            is EndpointToewijzing.Module -> require(toewijzing.module)
        }
    }

    /** The outcome of every registered module flag for the tenant [oin], by module name. */
    fun status(oin: Oin): Map<String, Boolean> = modules.associate { it.module to isEnabled(it, oin) }

    /** The module names each registered module requires, directly or through other modules, sorted by name. */
    fun dependencies(): Map<String, List<String>> = modules.associate { it.module to it.allRequired.map { required -> required.module }.sorted() }

    /** Tenant and module pairs where the flag is on but the tenant lacks the configuration: the module is off and the operator should know. */
    fun flagZonderConfiguratie(): List<ModuleBeoordeling> = matrix().filter { it.flag && !it.geconfigureerd }

    /** The matrix over all tenants and all registered modules: configured, flag and usable. */
    fun matrix(): List<ModuleBeoordeling> = directory.tenants.flatMap { tenant -> modules.map { beoordeel(it, tenant.oin) } }
}