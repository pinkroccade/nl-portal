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
package nl.nlportal.architecture.fixtures.httpvio

import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

class BuildsWebClient {
    fun own(): WebClient = WebClient.create()
}

class BuildsWebClientWithBuilder {
    fun own(): WebClient = WebClient.builder().baseUrl("https://bron.example").build()
}

class HoldsAWebClient(
    private val client: WebClient,
) {
    fun ping() = client.get()
}

class Wiretaps {
    fun client(): HttpClient = HttpClient.create().wiretap(true)
}

class LogsRequestDetails {
    fun strategies(): ExchangeStrategies = ExchangeStrategies.builder().codecs { it.defaultCodecs().enableLoggingRequestDetails(true) }.build()
}

class UsesRestClient {
    fun client(): org.springframework.web.client.RestClient =
        org.springframework.web.client.RestClient
            .create()
}

class UsesJavaHttpClient {
    fun client(): java.net.http.HttpClient =
        java.net.http.HttpClient
            .newHttpClient()
}

class OpensUrlConnection {
    fun open(): Any =
        java.net
            .URI("https://bron.example")
            .toURL()
            .openConnection()
}

class StoresAKoppelingClient(
    private val client: nl.nlportal.core.koppeling.KoppelingClient,
) {
    fun naam() = client.naam
}

class ReadsUrlStream {
    fun lees(): Any =
        java.net
            .URI("https://bron.example")
            .toURL()
            .openStream()
}

class ReadsUrlContent {
    fun lees(): Any? =
        java.net
            .URI("https://bron.example")
            .toURL()
            .content
}

class HoldsUrlConnection(
    private val verbinding: java.net.HttpURLConnection,
) {
    fun status() = verbinding.responseCode
}