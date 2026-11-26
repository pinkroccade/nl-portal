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
package nl.nlportal.kvk

import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.kvk.service.KvkService

/**
 * De permission-flag van de KvK-module: `permission.kvk.enabled`. KvK heeft per gemeente alleen een koppeling (`koppelingen.kvk`) en
 * geen `modules.kvk`; de module is bruikbaar voor een tenant met die koppeling (ADR 0021, 0026).
 */
val KVK_MODULE = ModuleFlag("kvk", koppelingen = setOf(KvkService.KOPPELING))