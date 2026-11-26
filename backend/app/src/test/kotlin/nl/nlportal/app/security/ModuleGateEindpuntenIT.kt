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
package nl.nlportal.app.security

import nl.nlportal.app.TestApplication
import nl.nlportal.core.modulegate.ModuleEndpoints
import nl.nlportal.graphql.ModuleGateInstrumentation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.FileSystemResource
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.method.HandlerMethod

/**
 * De centrale modulegate op de echte applicatie (ADR 0026, T22): elk schemaveld en elk REST-pad hoort bij een module of bij de kern,
 * en GraphQL-introspectie staat uit zoals de app die meelevert.
 */
@SpringBootTest(classes = [TestApplication::class])
@Tag("integration")
class ModuleGateEindpuntenIT {
    @Autowired
    lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    lateinit var endpoints: ModuleEndpoints

    @Autowired
    lateinit var graphQlSource: GraphQlSource

    @Autowired
    lateinit var instrumentation: ModuleGateInstrumentation

    @Test
    fun `every REST path belongs to a module or the core`() {
        val zonderRegistratie =
            handlerMapping.handlerMethods
                .map { (info, methode) -> info.toString() to methode }
                .filter { (_, methode: HandlerMethod) -> endpoints.van(methode.beanType) == null }
                .map { (info, _) -> info }

        assertThat(zonderRegistratie).describedAs("REST-paden zonder @ModuleEndpoint").isEmpty()
        assertThat(handlerMapping.handlerMethods).isNotEmpty()
    }

    @Test
    fun `every GraphQL query and mutation belongs to a module or the core`() {
        val schema = graphQlSource.schema()
        val wortelvelden =
            listOfNotNull(schema.queryType, schema.mutationType, schema.subscriptionType)
                .flatMap { type -> type.fieldDefinitions.map { "${type.name}.${it.name}" } }

        // Een veld waar geen resolver voor bestaat (een module die niet aan staat) heeft geen data fetcher en dus niets om te gate.
        val zonderRegistratie =
            wortelvelden.filter { coordinaat ->
                val (type, veld) = coordinaat.split('.')
                schema.codeRegistry.hasDataFetcher(graphql.schema.FieldCoordinates.coordinates(type, veld)) && coordinaat !in instrumentation.wortelvelden
            }

        assertThat(zonderRegistratie).describedAs("GraphQL-velden met een data fetcher maar zonder @ModuleEndpoint").isEmpty()
        assertThat(instrumentation.wortelvelden).isNotEmpty()
    }

    @Test
    fun `GraphQL introspection is off in the shipped configuration`() {
        val geleverd =
            YamlPropertiesFactoryBean()
                .apply { setResources(FileSystemResource("src/main/resources/config/application.yml")) }
                .getObject()!!

        assertThat(geleverd.getProperty("spring.graphql.schema.introspection.enabled")).isEqualTo("false")
    }

    companion object {
        /** De geleverde instelling voor introspectie, zodat de test de echte werking beproeft en niet een eigen kopie. */
        @JvmStatic
        @DynamicPropertySource
        fun geleverdeIntrospectie(registry: DynamicPropertyRegistry) {
            val geleverd =
                YamlPropertiesFactoryBean()
                    .apply { setResources(FileSystemResource("src/main/resources/config/application.yml")) }
                    .getObject()!!
            registry.add("spring.graphql.schema.introspection.enabled") { geleverd.getProperty("spring.graphql.schema.introspection.enabled") }
        }
    }

    @Test
    fun `an introspection query is refused`() {
        val resultaat = graphQlSource.graphQl().execute("{ __schema { queryType { name } } }")

        assertThat(resultaat.errors).isNotEmpty()
        assertThat(resultaat.getData<Any>()).isNull()
    }
}