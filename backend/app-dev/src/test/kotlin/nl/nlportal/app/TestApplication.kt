/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.util.concurrent.CopyOnWriteArraySet
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer

/**
 * Holds the set of GraphQL field names that Spring detected as "unmapped"
 * (i.e. declared in SDL but with no registered data fetcher bean).
 * Populated at startup via inspectSchemaMappings.
 */
class UnmappedGraphQlFields {
    val queryFields: MutableSet<String> = CopyOnWriteArraySet()
    val mutationFields: MutableSet<String> = CopyOnWriteArraySet()
}

/**
 * Test application that uses the REAL OauthSecurityAutoConfiguration.
 * Unlike other module TestApplication classes, this does NOT exclude security.
 * The login (BFF) runs against the test tenant file; no identity provider is needed as long as no test logs in.
 */
@SpringBootApplication
class TestApplication {
    fun main(args: Array<String>) {
        runApplication<TestApplication>(*args)
    }

    @Bean
    fun unmappedGraphQlFields(): UnmappedGraphQlFields = UnmappedGraphQlFields()

    @Bean
    fun graphQlSourceBuilderCustomizer(unmapped: UnmappedGraphQlFields): GraphQlSourceBuilderCustomizer =
        GraphQlSourceBuilderCustomizer { builder ->
            builder.inspectSchemaMappings { report ->
                report.unmappedFields().forEach { coordinates ->
                    when (coordinates.typeName) {
                        "Query" -> unmapped.queryFields.add(coordinates.fieldName)
                        "Mutation" -> unmapped.mutationFields.add(coordinates.fieldName)
                    }
                }
            }
        }
}