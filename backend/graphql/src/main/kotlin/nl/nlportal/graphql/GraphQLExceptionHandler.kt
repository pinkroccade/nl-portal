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
import io.github.oshai.kotlinlogging.KotlinLogging
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
            .message(melding(ex))
            .also { error ->
                when (ex) {
                    is ModuleNotAvailableException -> error.extensions(mapOf("code" to "MODULE_NOT_AVAILABLE", "module" to ex.module))

                    // Foutdetails van een bron gaan niet naar de browser: alleen een generieke code en de referentie voor de log.
                    // De melding van een tenant- of moduleconfiguratiefout bevat de OIN of de naam van een instelling: die gaat alleen naar de log.
                    is ModuleNotConfiguredException -> error.extensions(mapOf("code" to "MODULE_NIET_GECONFIGUREERD"))

                    is TenantContextMissingException -> error.extensions(mapOf("code" to "TENANT_ONTBREEKT"))

                    is KoppelingException -> error.extensions(mapOf("code" to "KOPPELING_FOUT", "correlatieId" to ex.correlatieId))
                }
            }.path(env.executionStepInfo.path)
            .location(env.field.sourceLocation)
            .build()

    private fun melding(ex: Throwable): String? =
        when (ex) {
            is ModuleNotConfiguredException, is TenantContextMissingException -> {
                log.warn { "GraphQL-fout met configuratiedetails (alleen in de log): ${ex.message}" }
                if (ex is ModuleNotConfiguredException) "De module is niet beschikbaar" else "De aanvraag kan niet worden verwerkt"
            }

            else -> {
                ex.message
            }
        }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}