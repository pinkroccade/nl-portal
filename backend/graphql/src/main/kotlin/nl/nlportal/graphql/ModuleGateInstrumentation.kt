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
package nl.nlportal.graphql

import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import kotlinx.coroutines.reactor.mono
import nl.nlportal.core.modulegate.EndpointToewijzing
import nl.nlportal.core.modulegate.ModuleEndpoints
import nl.nlportal.core.modulegate.ModuleGate
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.execution.ContextPropagationHelper
import reactor.util.context.Context
import org.springframework.util.ReflectionUtils
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The GraphQL side of the central module gate (ADR 0026). Every root field (query, mutation, subscription) first passes the
 * [ModuleGate] for the tenant of the request, before its data fetcher runs: a module that is not usable answers
 * `MODULE_NOT_AVAILABLE`, a root field that no registered controller ([nl.nlportal.core.modulegate.ModuleEndpoint]) maps is denied
 * (deny by default). Nested fields belong to their root field and are not checked again. Introspection fields (`__schema`,
 * `__type`) are not data and stay with the introspection setting of the application.
 */
class ModuleGateInstrumentation(
    private val endpoints: ModuleEndpoints,
    private val gate: ModuleGate,
) : SimplePerformantInstrumentation() {
    /** The root fields (`Query.getBerichten`) of the registered controllers with their assignment, resolved on first use. */
    val wortelvelden: Map<String, EndpointToewijzing> by lazy {
        buildMap {
            endpoints.registraties.forEach { (type, toewijzing) -> wortelvelden(type).forEach { put(it, toewijzing) } }
        }
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?,
    ): DataFetcher<*> {
        val stap = parameters.executionStepInfo
        val schema = parameters.executionContext.graphQLSchema
        val wortelTypes = listOfNotNull(schema.queryType, schema.mutationType, schema.subscriptionType).map { it.name }
        val veld = stap.fieldDefinition?.name
        val parent = stap.objectType?.name
        if (veld == null || parent == null || stap.path.level != 1 || parent !in wortelTypes || veld.startsWith("__")) return dataFetcher

        val coordinaat = "$parent.$veld"
        return DataFetcher<Any?> { env ->
            // The tenant lives in the Reactor context of the web request; Spring GraphQL carries it in the context snapshot of the request.
            val reactorContext = ContextPropagationHelper.captureFrom(env.graphQlContext).updateContext(Context.empty())
            val toegestaan: CompletableFuture<Unit?> =
                mono { gate.controleer(wortelvelden[coordinaat], coordinaat) }
                    .contextWrite(reactorContext)
                    .toFuture()
            toegestaan.thenCompose<Any?> {
                when (val resultaat = dataFetcher.get(env)) {
                    is CompletionStage<*> -> resultaat.toCompletableFuture().thenApply { it as Any? }
                    is Mono<*> -> resultaat.contextWrite(reactorContext).toFuture().thenApply { it as Any? }
                    else -> CompletableFuture.completedFuture(resultaat)
                }
            }
        }
    }

    private fun wortelvelden(type: Class<*>): List<String> {
        val opKlasse = AnnotatedElementUtils.findMergedAnnotation(type, SchemaMapping::class.java)
        val velden = mutableListOf<String>()
        ReflectionUtils.doWithMethods(type) { methode ->
            val mapping = AnnotatedElementUtils.findMergedAnnotation(methode, SchemaMapping::class.java) ?: return@doWithMethods
            val typeNaam = mapping.typeName.ifEmpty { opKlasse?.typeName.orEmpty() }
            if (typeNaam in WORTELTYPES) velden += "$typeNaam.${mapping.field.ifEmpty { methode.name }}"
        }
        return velden
    }

    private companion object {
        val WORTELTYPES = setOf("Query", "Mutation", "Subscription")
    }
}