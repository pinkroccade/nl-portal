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

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import nl.nlportal.core.modulegate.EndpointNietGeregistreerdException
import nl.nlportal.core.modulegate.EndpointToewijzing
import nl.nlportal.core.modulegate.ModuleEndpoint
import nl.nlportal.core.modulegate.ModuleEndpoints
import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.modulegate.ModuleNotAvailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping

/** Elk root-veld loopt eerst door de gate: een module die uit staat en een veld zonder registratie worden geweigerd, de kern en nested velden niet. */
class ModuleGateInstrumentationTest {
    private val module = ModuleFlag("voorbeeld")

    @ModuleEndpoint("voorbeeld")
    class ModuleController {
        @QueryMapping
        fun voorbeeld() = "voorbeeld"

        @MutationMapping
        fun wijzig() = "gewijzigd"

        @SchemaMapping(typeName = "Voorbeeld", field = "detail")
        fun detail() = "detail"
    }

    @ModuleEndpoint(ModuleEndpoint.KERN)
    class KernController {
        @QueryMapping
        fun kern() = "kern"
    }

    private fun graphQl(moduleAan: Boolean): GraphQL {
        val beans = StaticListableBeanFactory(mapOf("module" to ModuleController(), "kern" to KernController()))
        val endpoints = ModuleEndpoints(beans, listOf(module))
        val gate: ModuleGate = mock()
        // De echte gate beslist op flag en configuratie; hier telt alleen wat de instrumentation met de uitkomst doet.
        org.mockito.kotlin.KStubbing(gate).apply {
            onBlocking { controleer(anyOrNull(), any()) }.doAnswer { aanroep ->
                when (val toewijzing = aanroep.arguments[0] as EndpointToewijzing?) {
                    null -> throw EndpointNietGeregistreerdException(aanroep.arguments[1] as String)
                    is EndpointToewijzing.Module -> if (!moduleAan) throw ModuleNotAvailableException(toewijzing.module.module)
                    else -> Unit
                }
            }
        }
        val schema =
            SchemaGenerator().makeExecutableSchema(
                SchemaParser().parse(
                    """
                    type Query { voorbeeld: Voorbeeld kern: String onbekend: String }
                    type Mutation { wijzig: String }
                    type Voorbeeld { detail: String }
                    """.trimIndent(),
                ),
                RuntimeWiring
                    .newRuntimeWiring()
                    .type("Query") { it.dataFetcher("voorbeeld") { Any() }.dataFetcher("kern") { "kern" }.dataFetcher("onbekend") { "onbekend" } }
                    .type("Mutation") { it.dataFetcher("wijzig") { "gewijzigd" } }
                    .type("Voorbeeld") { it.dataFetcher("detail") { "detail" } }
                    .build(),
            )
        return GraphQL.newGraphQL(schema).instrumentation(ModuleGateInstrumentation(endpoints, gate)).build()
    }

    private fun voerUit(
        graphQl: GraphQL,
        query: String,
    ) = graphQl.execute(ExecutionInput.newExecutionInput().query(query).build())

    @Test
    fun `a root field of a usable module returns its data and its nested fields are not checked again`() {
        val resultaat = voerUit(graphQl(moduleAan = true), "{ voorbeeld { detail } }")

        assertTrue(resultaat.errors.isEmpty(), resultaat.errors.toString())
        assertEquals(mapOf("voorbeeld" to mapOf("detail" to "detail")), resultaat.getData<Map<String, Any>>())
    }

    @Test
    fun `a root field of a module that is off is refused with the not-available error`() {
        val resultaat = voerUit(graphQl(moduleAan = false), "{ voorbeeld { detail } }")

        assertEquals(1, resultaat.errors.size)
        assertTrue(
            resultaat.errors
                .single()
                .message
                .contains("Module 'voorbeeld' is niet beschikbaar"),
            resultaat.errors.toString(),
        )
    }

    @Test
    fun `a mutation of a module that is off is refused too`() {
        val resultaat = voerUit(graphQl(moduleAan = false), "mutation { wijzig }")

        assertTrue(
            resultaat.errors
                .single()
                .message
                .contains("Module 'voorbeeld' is niet beschikbaar"),
            resultaat.errors.toString(),
        )
    }

    @Test
    fun `a root field of the core passes whatever the module state is`() {
        val resultaat = voerUit(graphQl(moduleAan = false), "{ kern }")

        assertTrue(resultaat.errors.isEmpty(), resultaat.errors.toString())
        assertEquals(mapOf("kern" to "kern"), resultaat.getData<Map<String, Any>>())
    }

    @Test
    fun `a root field that no registered controller maps is denied by default`() {
        val resultaat = voerUit(graphQl(moduleAan = true), "{ onbekend }")

        assertTrue(
            resultaat.errors
                .single()
                .message
                .contains("Endpoint is niet beschikbaar"),
            resultaat.errors.toString(),
        )
    }

    @Test
    fun `typename at the root is not data and is not gated`() {
        val resultaat = voerUit(graphQl(moduleAan = false), "{ __typename }")

        assertTrue(resultaat.errors.isEmpty(), resultaat.errors.toString())
    }

    @Test
    fun `the root fields of the registered controllers are mapped to their assignment`() {
        val beans = StaticListableBeanFactory(mapOf("module" to ModuleController(), "kern" to KernController()))
        val instrumentation = ModuleGateInstrumentation(ModuleEndpoints(beans, listOf(module)), mock())

        assertEquals(setOf("Query.voorbeeld", "Mutation.wijzig", "Query.kern"), instrumentation.wortelvelden.keys)
        assertTrue(instrumentation.wortelvelden.getValue("Query.kern") is EndpointToewijzing.Kern)
        assertTrue(instrumentation.wortelvelden.getValue("Query.voorbeeld") is EndpointToewijzing.Module)
    }
}