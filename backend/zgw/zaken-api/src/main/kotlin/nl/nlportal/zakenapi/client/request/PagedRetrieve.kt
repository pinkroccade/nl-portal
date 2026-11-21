/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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

import nl.nlportal.core.koppeling.ResultPage

interface PagedRetrieve<O : PagedRetrieve<O, T>, T> : Retrieve<ResultPage<T>> {
    fun page(page: Int): O

    fun pageSize(pageSize: Int): O

    /** Alle pagina's; een volgende pagina wordt alleen gevolgd onder de basis-URL van de koppeling van de tenant. */
    suspend fun retrieveAll(): List<T>
}