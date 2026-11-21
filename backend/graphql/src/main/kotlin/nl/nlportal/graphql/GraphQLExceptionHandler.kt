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
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.util.ModuleNotConfiguredException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter

class GraphQLExceptionHandler : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? =
        GraphqlErrorBuilder
            .newError()
            .message(if (ex is ModuleNotConfiguredException || ex is TenantContextMissingException) GENERIEKE_MELDING else ex.message)
            .also { error ->
                when (ex) {
                    is ModuleNotAvailableException -> error.extensions(mapOf("code" to "MODULE_NOT_AVAILABLE", "module" to ex.module))

                    // Foutdetails van een bron gaan niet naar de browser: alleen een generieke code en de referentie voor de log.
                    // De melding kan de OIN of de naam van een instelling noemen: die gaat niet naar de browser.
                    is ModuleNotConfiguredException, is TenantContextMissingException -> error.extensions(mapOf("code" to "CONFIGURATIE_ONTBREEKT"))

                    is KoppelingException -> error.extensions(mapOf("code" to "KOPPELING_FOUT", "correlatieId" to ex.correlatieId))
                }
            }.path(env.executionStepInfo.path)
            .location(env.field.sourceLocation)
            .build()

    private companion object {
        const val GENERIEKE_MELDING = "De gevraagde functie is niet beschikbaar"
    }
}