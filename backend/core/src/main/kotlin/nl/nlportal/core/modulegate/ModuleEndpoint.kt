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

/**
 * Registers a GraphQL controller or REST resource at the central module gate (ADR 0026). Deny by default: an endpoint
 * without this annotation is not reachable, and an ArchUnit rule requires it on every controller.
 *
 * - `@ModuleEndpoint("berichten")`: reachable when the module is usable for the tenant of the request (flag and configuration).
 * - `@ModuleEndpoint(ModuleEndpoint.KERN)`: part of the core, reachable for every tenant.
 * - `@ModuleEndpoint("zaken", overgang = true)`: the module is not on the gate yet (follow-up of #521); its endpoints stay
 *   reachable as before. The ArchUnit ratchet only lets the number of transitional endpoints shrink.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ModuleEndpoint(
    val module: String,
    val overgang: Boolean = false,
) {
    companion object {
        /** The "module" of endpoints that belong to the core and are never switched off. */
        const val KERN = "kern"
    }
}