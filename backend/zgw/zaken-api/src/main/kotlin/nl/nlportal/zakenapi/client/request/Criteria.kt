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
package nl.nlportal.zakenapi.client.request

import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

/**
 * De zoekcriteria van een aanvraag. De bouwers zijn niet `suspend`, maar een deel van de waarden hangt af van de tenant (de URL
 * van de koppeling, een schakelaar in het tenantbestand): [later] stelt die uit tot [bouw], dat bij het uitvoeren draait.
 */
internal class Criteria<V : Any> {
    private val waarden = LinkedMultiValueMap<String, V>()
    private val uitgesteld = mutableListOf<suspend (MultiValueMap<String, V>) -> Unit>()

    fun add(
        naam: String,
        waarde: V,
    ) {
        waarden.add(naam, waarde)
    }

    fun put(
        naam: String,
        waarde: List<V>,
    ) {
        waarden[naam] = waarde.toMutableList()
    }

    fun later(blok: suspend (MultiValueMap<String, V>) -> Unit) {
        uitgesteld += blok
    }

    suspend fun bouw(): MultiValueMap<String, V> {
        val resultaat = waarden.deepCopy()
        uitgesteld.forEach { it(resultaat) }
        return resultaat
    }
}