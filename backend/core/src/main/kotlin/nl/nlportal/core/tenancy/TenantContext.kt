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
package nl.nlportal.core.tenancy

import kotlinx.coroutines.reactor.ReactorContext
import reactor.core.publisher.Mono
import kotlin.coroutines.coroutineContext

class TenantContextMissingException : IllegalStateException("Geen tenant in de Reactor Context: het request liep niet via de tenantresolutie, of de context is verloren")

/**
 * De tenant van het huidige request, in de Reactor Context (geen `ThreadLocal`) en dus de enige leesroute.
 * Lezen is fail-closed: zonder tenant volgt een fout, nooit een standaardtenant. Alleen `suspend`- en `Mono`-code
 * leest de tenant.
 */
object TenantContext {
    /** De tenant uit een Reactor-context, of `null` zonder tenant; voor code die de context zelf in handen heeft (bijvoorbeeld GraphQL). */
    fun van(context: reactor.util.context.ContextView): Oin? = context.getOrEmpty<Oin>(SLEUTEL).orElse(null)

    internal const val SLEUTEL: String = "nl.nlportal.core.tenancy.Oin"

    /** Voor `suspend`-code: leest de Reactor Context die kotlinx-coroutines-reactor in de coroutinecontext zet. */
    suspend fun current(): Oin =
        coroutineContext[ReactorContext]
            ?.context
            ?.getOrEmpty<Oin>(SLEUTEL)
            ?.orElse(null)
            ?: throw TenantContextMissingException()

    /** Voor reactieve code. */
    fun currentMono(): Mono<Oin> =
        Mono.deferContextual { context ->
            context.getOrEmpty<Oin>(SLEUTEL).map { Mono.just(it) }.orElseGet { Mono.error(TenantContextMissingException()) }
        }
}