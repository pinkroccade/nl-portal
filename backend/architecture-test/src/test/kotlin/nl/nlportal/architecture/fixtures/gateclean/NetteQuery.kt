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
package nl.nlportal.architecture.fixtures.gateclean

import nl.nlportal.core.modulegate.ModuleEndpoint
import org.springframework.stereotype.Controller

/** Een geregistreerde controller van een module op de gate, een van de kern en een van een module in overgang. */
@ModuleEndpoint("berichten")
@Controller
class NetteQuery

@ModuleEndpoint(ModuleEndpoint.KERN)
@Controller
class KernResource

@ModuleEndpoint("zaken", overgang = true)
@Controller
class ZakenInOvergang