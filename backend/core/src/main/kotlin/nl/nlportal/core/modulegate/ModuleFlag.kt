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

import nl.nlportal.core.featureflag.FeatureFlag
import nl.nlportal.core.featureflag.ToggleType
import nl.nlportal.core.tenancy.TenantDefinition

/**
 * The permission flag that switches a module on or off per tenant. The key is `permission.<module>.enabled` (ADR 0020, 0026);
 * the flag is fail-closed: a missing flag or a failing flag source means off. A module registers its flag as a bean so that the
 * gate, the frontend module list and the module matrix know it. `ops.*` flags stay for emergency brakes.
 *
 * A module is usable for a tenant when its flag is on **and** the tenant has the configuration the module needs
 * ([koppelingen], [moduleConfiguratie]); a flag without configuration means off (ADR 0021, 0026).
 *
 * [requires] lists the module flags this module depends on (berichten requires documentenapis). The module is only
 * usable when its own conditions and those of every required module, also the indirect ones, hold: an invalid combination
 * is fail-closed.
 *
 * @param koppelingen the names of the `koppelingen` in the tenant file the module needs.
 * @param moduleConfiguratie the key under `modules` in the tenant file the module needs, or `null` when it has none.
 */
data class ModuleFlag(
    val module: String,
    val requires: List<ModuleFlag> = emptyList(),
    val koppelingen: Set<String> = emptySet(),
    val moduleConfiguratie: String? = null,
) {
    val flag = FeatureFlag("permission.$module.enabled", ToggleType.PERMISSION)

    /** Every module this module needs, directly or through other modules, without duplicates. */
    val allRequired: List<ModuleFlag> get() = requires.flatMap { listOf(it) + it.allRequired }.distinct()

    /** Whether [tenant] has the koppelingen and the module configuration this module itself needs (not those of [requires]). */
    fun isConfiguredFor(tenant: TenantDefinition): Boolean = koppelingen.all { it in tenant.koppelingen } && (moduleConfiguratie == null || moduleConfiguratie in tenant.modules)
}