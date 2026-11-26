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
package nl.nlportal.zaken

import nl.nlportal.catalogiapi.client.CatalogiApiClient
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.documentenapi.DOCUMENTENAPIS_MODULE
import nl.nlportal.zakenapi.client.ZakenApiClient
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient

/**
 * De permission-flag van de zakenmodule: `permission.zaken.enabled`. De module serveert zaakdocumenten en vereist daarom
 * documentenapis. Hij is alleen bruikbaar voor een tenant met `koppelingen.zakenapi`, `koppelingen.catalogiapi` en
 * `koppelingen.objectenapi` en `modules.zakenapi` in het tenantbestand (ADR 0021, 0026). De gate-naam is `zaken`, zoals de
 * frontend de oude property `zakenapi` al noemt.
 */
val ZAKEN_MODULE =
    ModuleFlag(
        "zaken",
        requires = listOf(DOCUMENTENAPIS_MODULE),
        koppelingen = setOf(ZakenApiClient.MODULE, CatalogiApiClient.MODULE, ObjectsApiClient.MODULE),
        moduleConfiguratie = ZakenApiClient.MODULE,
    )