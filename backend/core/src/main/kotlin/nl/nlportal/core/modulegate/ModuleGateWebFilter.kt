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
package nl.nlportal.core.modulegate

import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.method.HandlerMethod
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Optional

/**
 * The REST side of the central module gate (ADR 0026). It looks up the controller that will handle the request and asks the
 * [ModuleGate] whether that endpoint may be reached for the tenant of the request: a module that is not usable answers 503, an
 * endpoint without registration ([ModuleEndpoint]) answers 404 (deny by default). It runs after the security filter chain, so
 * an anonymous request to a secured path is still refused by security first, and after the tenant resolution.
 */
class ModuleGateWebFilter(
    private val handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
    private val endpoints: ModuleEndpoints,
    private val gate: ModuleGate,
) : WebFilter,
    Ordered {
    override fun getOrder() = ORDER

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> =
        weigering(exchange).flatMap { uitkomst ->
            uitkomst.map { antwoord(exchange, it) }.orElseGet { chain.filter(exchange) }
        }

    /** Empty [Optional] when the request may continue, otherwise the refusal. */
    private fun weigering(exchange: ServerWebExchange): Mono<Optional<Weigering>> =
        Flux
            .fromIterable(handlerMappings.orderedStream().toList())
            .concatMap { it.getHandler(exchange) }
            .next()
            .flatMap { handler ->
                if (handler is HandlerMethod) {
                    mono { controleer(handler) }.map { Optional.of(it) }
                } else {
                    Mono.empty()
                }
            }.defaultIfEmpty(Optional.empty())

    private suspend fun controleer(handler: HandlerMethod): Weigering? =
        try {
            gate.controleer(endpoints.van(handler.beanType), handler.beanType.name)
            null
        } catch (e: ModuleNotAvailableException) {
            Weigering(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", e.message)
        } catch (e: EndpointNietGeregistreerdException) {
            Weigering(HttpStatus.NOT_FOUND, "Not Found", e.message)
        }

    private fun antwoord(
        exchange: ServerWebExchange,
        weigering: Weigering,
    ): Mono<Void> {
        val response = exchange.response
        response.statusCode = weigering.status
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        val body =
            """{"type":"about:blank","title":"${weigering.title}","status":${weigering.status.value()},"detail":"${weigering.detail}"}"""
                .toByteArray()
        val factory: DataBufferFactory = response.bufferFactory()
        return response.writeWith(Mono.just(factory.wrap(body)))
    }

    /** [detail] is a fixed message that names at most a module from the code, never user input. */
    private class Weigering(
        val status: HttpStatus,
        val title: String,
        val detail: String?,
    )

    companion object {
        /** After the security filter chain (-100), so security refuses an anonymous request first. */
        const val ORDER = -50
    }
}