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
 * Registers a nested GraphQL field (a `@SchemaMapping` below a root field) at the central module gate. Use it where a module has no
 * root field of its own and is reached through a field of another module, for example `Zaak.besluiten`: the gate then also checks the
 * module of the field, so the module cannot be reached when its flag is off, even when the module of the root field is on.
 *
 * The fields of the module's own controller ([ModuleEndpoint]) do not need it; the gate decides on the root field.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ModuleVeld(
    /** The module (with a [ModuleFlag] bean) the field belongs to. */
    val module: String,
)