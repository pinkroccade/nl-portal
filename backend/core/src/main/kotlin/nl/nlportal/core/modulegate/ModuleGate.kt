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

import dev.openfeature.sdk.EvaluationContext
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.nlportal.core.featureflag.FeatureContextFactory
import nl.nlportal.core.featureflag.FeatureGate
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import org.springframework.security.core.Authentication

private val logger = KotlinLogging.logger {}

/** Why a module is usable for a tenant or not; only [BRUIKBAAR] lets a request through. */
enum class ModuleBeschikbaarheid {
    BRUIKBAAR,

    /** The tenant is not in the [TenantDirectory], or there is no directory (no tenancy). */
    TENANT_ONBEKEND,

    /** The tenant has no `modules.<naam>` or `koppelingen.<naam>` for the module; a flag that is on does not change that. */
    NIET_GECONFIGUREERD,

    /** The permission flag is off, missing or could not be evaluated (fail-closed). */
    FLAG_UIT,

    /** A module this module needs ([ModuleFlag.requires]) is itself not usable. */
    VEREISTE_NIET_BRUIKBAAR,
}

/**
 * Gate of a module. The module's beans are always registered; a request for a module that is not usable for the tenant
 * of the request is refused. The GraphQL schema does not change: the fields stay visible in introspection.
 *
 * A module is **usable** for a tenant when the tenant has configuration for it (`modules.<naam>` or `koppelingen.<naam>`,
 * loaded at startup and immutable) **and** the permission flag `permission.<module>.enabled` is on for the OIN of the
 * tenant, **and** every module it requires ([ModuleFlag.requires]) is usable too. The flag is evaluated with the OIN in
 * the evaluation context, so it can differ per tenant. Fail-closed: a missing flag, an evaluation error, an unknown
 * tenant and a missing configuration all make the module unusable. Because the configuration is checked first, a flag that
 * is switched on before the restart (the flagd file is polled) never makes a module without configuration usable, and
 * a tenant never gets the outcome of another tenant.
 *
 * The tenant comes from the [TenantContext] (also on anonymous paths and the webhook, where there is no token) and is
 * read in the suspend functions; the context is passed explicitly, never read from thread-bound state, which does not
 * survive thread hops in WebFlux.
 */
class ModuleGate(
    private val featureGate: FeatureGate,
    private val featureContextFactory: FeatureContextFactory,
    private val modules: List<ModuleFlag>,
    private val tenants: TenantDirectory? = null,
) {
    /** Throws [ModuleNotAvailableException] when [module] is not usable for the tenant of the [TenantContext], or for [tenant] when given. */
    suspend fun requireUsable(
        module: ModuleFlag,
        authentication: Authentication? = null,
        tenant: Oin? = null,
    ) {
        if (!isUsable(module, authentication, tenant)) throw ModuleNotAvailableException(module.module)
    }

    /** Whether [module] is usable for [tenant], by default the tenant of the [TenantContext] (without one: [nl.nlportal.core.tenancy.TenantContextMissingException]). */
    suspend fun isUsable(
        module: ModuleFlag,
        authentication: Authentication? = null,
        tenant: Oin? = null,
    ): Boolean = isUsable(tenant ?: TenantContext.current(), module, authentication)

    fun isUsable(
        oin: Oin,
        module: ModuleFlag,
        authentication: Authentication? = null,
    ): Boolean = beschikbaarheid(oin, module, authentication) == ModuleBeschikbaarheid.BRUIKBAAR

    /** The outcome for [module] and [oin], with the reason when it is not usable. */
    fun beschikbaarheid(
        oin: Oin,
        module: ModuleFlag,
        authentication: Authentication? = null,
    ): ModuleBeschikbaarheid {
        val tenant = tenants?.forOin(oin) ?: return ModuleBeschikbaarheid.TENANT_ONBEKEND
        return beoordeel(tenant, module, featureContextFactory.create(authentication, oin))
    }

    /** The outcome of every registered module for [oin], by module name. */
    fun status(oin: Oin): Map<String, ModuleBeschikbaarheid> = modules.associate { it.module to beschikbaarheid(oin, it) }

    private fun beoordeel(
        tenant: TenantDefinition,
        module: ModuleFlag,
        context: EvaluationContext,
    ): ModuleBeschikbaarheid =
        when {
            !isGeconfigureerd(tenant, module) -> ModuleBeschikbaarheid.NIET_GECONFIGUREERD
            !flagAan(module, context) -> ModuleBeschikbaarheid.FLAG_UIT
            module.requires.any { beoordeel(tenant, it, context) != ModuleBeschikbaarheid.BRUIKBAAR } -> ModuleBeschikbaarheid.VEREISTE_NIET_BRUIKBAAR
            else -> ModuleBeschikbaarheid.BRUIKBAAR
        }

    private fun isGeconfigureerd(
        tenant: TenantDefinition,
        module: ModuleFlag,
    ) = module.configuratieNaam in tenant.modules || module.configuratieNaam in tenant.koppelingen

    private fun flagAan(
        module: ModuleFlag,
        context: EvaluationContext,
    ): Boolean =
        try {
            featureGate.isEnabled(module.flag, context)
        } catch (e: Exception) {
            // Fail-closed; the message stays out of the log, a provider error can carry context.
            logger.warn { "Flag van module ${module.module} niet te evalueren (${e::class.simpleName}); de module blijft uit" }
            false
        }

    /**
     * Legacy, on the flag outcome of a given context (no tenant, no configuration check). The callers move to
     * [requireUsable] with the central enforcement of #521; do not use in new code.
     */
    fun isEnabled(
        module: ModuleFlag,
        context: EvaluationContext,
    ): Boolean = (listOf(module) + module.allRequired).all { flagAan(it, context) }

    /** Throws [ModuleNotAvailableException] when [module] is off for the request of [authentication]. Legacy, see [isEnabled]. */
    fun require(
        module: ModuleFlag,
        authentication: Authentication?,
    ) {
        if (!isEnabled(module, featureContextFactory.create(authentication))) {
            throw ModuleNotAvailableException(module.module)
        }
    }

    /** Legacy: [isEnabled] for every registered module, by module name. */
    fun status(context: EvaluationContext): Map<String, Boolean> = modules.associate { it.module to isEnabled(it, context) }

    /** The module names each registered module requires, directly or through other modules, sorted by name. */
    fun dependencies(): Map<String, List<String>> = modules.associate { it.module to it.allRequired.map { required -> required.module }.sorted() }
}