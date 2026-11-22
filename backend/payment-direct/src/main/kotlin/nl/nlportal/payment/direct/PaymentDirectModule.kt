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
 * De modulevlag van payment-direct: `permission.payment-direct.enabled`. De module heeft geen eigenschap `enabled` meer (die is
 * vervallen); bruikbaar voor een tenant is hij zodra `modules.payment-direct` er is en deze vlag voor die tenant aan staat.
 */
val PAYMENT_DIRECT_MODULE = ModuleFlag(DirectPaymentTenantConfiguratie.NAAM)