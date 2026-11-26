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
package nl.nlportal.berichten

import nl.nlportal.berichten.configuratie.BerichtenTenantConfiguratie
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.documentenapi.DOCUMENTENAPIS_MODULE

/**
 * The permission flag of berichten: `permission.berichten.enabled`. Berichten serves documents and so requires documentenapis. The module is
 * only usable for a tenant with `koppelingen.objectenapi` and `modules.berichten` in the tenant file (ADR 0021, 0026).
 */
val BERICHTEN_MODULE =
    ModuleFlag(
        "berichten",
        requires = listOf(DOCUMENTENAPIS_MODULE),
        koppelingen = setOf(ObjectsApiClient.MODULE),
        moduleConfiguratie = BerichtenTenantConfiguratie.NAAM,
    )