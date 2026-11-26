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

import nl.nlportal.core.testing.ModuleFlagBestand
import nl.nlportal.documentenapi.DOCUMENTENAPIS_MODULE

/** Eén flagbestand voor alle testcontexts van deze module (OpenFeature is een JVM-brede singleton); de vereiste module staat eerst, zodat `herstel` hem eerst weer aanzet. */
object ZakenFlagFile {
    val vlaggen = ModuleFlagBestand(listOf(DOCUMENTENAPIS_MODULE, ZAKEN_MODULE))
}