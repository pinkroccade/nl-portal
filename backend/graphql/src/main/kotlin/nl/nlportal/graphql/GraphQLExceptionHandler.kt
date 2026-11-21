/*
 * Copyright 2015-2025 Den Haag, Ritense, the Netherlands.
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

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.modulegate.ModuleNotAvailableException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.security.core.AuthenticationException

/**
 * Zet fouten om naar een GraphQL-fout met een stabiele `extensions.code`. `UNAUTHENTICATED` (geen identiteit: de sessie is verlopen of
 * ongeldig) laat de frontend de sessie als verlopen behandelen; `/graphql` geeft daarvoor geen HTTP 401 omdat anonieme queries bestaan.
 */
class GraphQLExceptionHandler : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? =
        GraphqlErrorBuilder
            .newError()
            // Bij een authenticatiefout nooit de melding van de bron doorgeven: alleen een generieke tekst.
            .message(if (ex is AuthenticationException) "Niet geauthenticeerd" else ex.message)
            .also { error ->
                when (ex) {
                    is ModuleNotAvailableException -> error.extensions(mapOf("code" to "MODULE_NOT_AVAILABLE", "module" to ex.module))

                    is AuthenticationException -> error.extensions(mapOf("code" to "UNAUTHENTICATED"))

                    // Foutdetails van een bron gaan niet naar de browser: alleen een generieke code en de referentie voor de log.
                    is KoppelingException -> error.extensions(mapOf("code" to "KOPPELING_FOUT", "correlatieId" to ex.correlatieId))
                }
            }.path(env.executionStepInfo.path)
            .location(env.field.sourceLocation)
            .build()
}