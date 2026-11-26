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
package nl.nlportal.payment.direct

import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.payment.direct.configuratie.DirectPaymentTenantConfiguratie

/**
 * De permission-flag van de payment-direct-module: `permission.payment-direct.enabled`. De module is alleen bruikbaar voor een tenant met
 * `modules.payment-direct`. Welke koppeling elk profiel gebruikt kiest het profiel zelf; dat het bestaat bewaakt de tenantvalidatie
 * ([DirectPaymentTenantConfiguratie.valideer]), dus de flag eist zelf geen koppeling. De postsale-webhook valt onder dezelfde gate: voor een
 * tenant waarvoor de module uit staat antwoordt hij 503 (ADR 0021, 0026).
 */
val PAYMENT_DIRECT_MODULE = ModuleFlag(DirectPaymentTenantConfiguratie.NAAM, moduleConfiguratie = DirectPaymentTenantConfiguratie.NAAM)