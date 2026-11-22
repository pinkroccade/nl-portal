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

/**
 * The permission flag that switches a module on or off per tenant. The key is `permission.<module>.enabled`; it is fail-closed.
 * A module registers its flag as a bean so that the frontend module list and the module status know it.
 * Without a flag definition the module is off.
 *
 * [requires] lists the module flags this module depends on (berichten requires documentenapis). The module is only
 * on when its own flag and every required flag, also those of the required modules, are on: an invalid combination
 * is fail-closed.
 */
data class ModuleFlag(
    val module: String,
    val requires: List<ModuleFlag> = emptyList(),
    /**
     * The name under which the module has its configuration in the tenant file: `modules.<name>` or `koppelingen.<name>`.
     * Defaults to the module name. A module is only usable for a tenant that has this configuration.
     */
    val configuratieNaam: String = module,
) {
    val flag = FeatureFlag("permission.$module.enabled", ToggleType.PERMISSION)

    /** Every module this module needs, directly or through other modules, without duplicates. */
    val allRequired: List<ModuleFlag> get() = requires.flatMap { listOf(it) + it.allRequired }.distinct()
}