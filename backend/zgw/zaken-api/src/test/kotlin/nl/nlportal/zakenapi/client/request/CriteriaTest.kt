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

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CriteriaTest {
    @Test
    fun `een uitgesteld blok dat een bestaande sleutel aanvult, wijzigt de criteria niet bij elke bouw opnieuw`() =
        runTest {
            val criteria = Criteria<Any>()
            criteria.add("rol", "initiator")
            criteria.later { it.add("rol", "belanghebbende") }

            val eerste = criteria.bouw()
            val tweede = criteria.bouw()

            assertThat(eerste["rol"]).containsExactly("initiator", "belanghebbende")
            assertThat(tweede["rol"]).containsExactly("initiator", "belanghebbende")
        }

    @Test
    fun `put vervangt de waarden van een sleutel`() =
        runTest {
            val criteria = Criteria<Any>()
            criteria.add("zaaktype", "a")
            criteria.put("zaaktype", listOf("b", "c"))

            assertThat(criteria.bouw()["zaaktype"]).containsExactly("b", "c")
        }
}