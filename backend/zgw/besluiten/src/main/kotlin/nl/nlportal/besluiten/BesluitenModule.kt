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
package nl.nlportal.besluiten

import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.documentenapi.DOCUMENTENAPIS_MODULE

/**
 * De permission-flag van de besluitenmodule: `permission.besluiten.enabled`. Besluiten heeft geen root-velden: de module wordt
 * bereikt via `Zaak.besluiten` (zakenmodule, geregistreerd met `@ModuleVeld("besluiten")`) en serveert besluitdocumenten, dus hij
 * vereist documentenapis. Besluiten heeft nog geen tenantconfiguratie (`modules.besluiten`) en geen koppeling in het tenantbestand
 * (T15, uitgesteld tot na de livegang): "bruikbaar" is hier de flag plus de vereiste modules. De bron komt nog uit gedeelde
 * instellingen; daarom blijft `NietGemigreerdeModule` het opstarten weigeren bij meer dan één tenant.
 */
val BESLUITEN_MODULE = ModuleFlag("besluiten", requires = listOf(DOCUMENTENAPIS_MODULE))