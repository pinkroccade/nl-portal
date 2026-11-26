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
package nl.nlportal.haalcentraalbrp

import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.haalcentraalbrp.tenant.BrpTenantConfiguratie

/**
 * De permission-flag van de BRP-module: `permission.haalcentraal-brp.enabled`. De naam is de modulenaam die de frontend al kende
 * (`haalcentraal-brp`); de gegevens in het tenantbestand blijven `koppelingen.brp` en `modules.brp`. De module is alleen bruikbaar voor een
 * tenant met allebei (ADR 0021, 0026).
 */
val BRP_MODULE =
    ModuleFlag(
        "haalcentraal-brp",
        koppelingen = setOf(BrpTenantConfiguratie.PERSONEN),
        moduleConfiguratie = BrpTenantConfiguratie.MODULE,
    )