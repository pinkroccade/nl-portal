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
package nl.nlportal.architecture.fixtures.httpvio.status

import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient

/** Alleen het filtertype, zoals `core.modulestatus`: mag met de typevrijstelling. */
class KentAlleenHetFiltertype {
    fun filter(): ExchangeFilterFunction = ExchangeFilterFunction { request, next -> next.exchange(request) }
}

/** Bouwt een eigen client: de typevrijstelling dekt dat niet. */
class BouwtOokEenClient {
    fun eigen(): WebClient = WebClient.create()
}