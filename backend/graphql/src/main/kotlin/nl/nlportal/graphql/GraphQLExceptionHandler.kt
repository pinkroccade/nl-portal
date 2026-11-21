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
import nl.nlportal.core.koppeling.KoppelingConfiguratieException
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.modulegate.ModuleNotAvailableException
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.util.ModuleNotConfiguredException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import java.util.UUID

/**
 * Zet een fout uit een data fetcher om naar een GraphQL-fout. Interne configuratiefouten (`ModuleNotConfiguredException`,
 * `TenantContextMissingException`, `KoppelingConfiguratieException`) bevatten OIN, koppeling- of headernamen en gaan dus
 * niet met hun melding naar de browser: de browser krijgt een vaste melding, een generieke code en een correlatieId. De log
 * noemt de code, het correlatieId, de klassenaam en de melding van de uitzondering (voor de beheerder), nooit de oorzaak
 * (`cause`) of een stacktrace.
 */
class GraphQLExceptionHandler : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? {
        val uitkomst =
            when (ex) {
                is ModuleNotAvailableException -> Uitkomst(ex.message, mapOf("code" to "MODULE_NOT_AVAILABLE", "module" to ex.module))

                // Foutdetails van een bron gaan niet naar de browser: alleen een generieke code en de referentie voor de log.
                is KoppelingException -> Uitkomst(ex.message, mapOf("code" to "KOPPELING_FOUT", "correlatieId" to ex.correlatieId))

                is ModuleNotConfiguredException -> intern(ex, "Module is niet beschikbaar", "MODULE_NIET_GECONFIGUREERD")

                is TenantContextMissingException -> intern(ex, "Niet geautoriseerd", "TENANT_ONTBREEKT")

                is KoppelingConfiguratieException -> intern(ex, "Koppeling is niet beschikbaar", "KOPPELING_FOUT")

                else -> Uitkomst(ex.message, null)
            }
        return GraphqlErrorBuilder
            .newError()
            .message(uitkomst.melding)
            .also { error -> uitkomst.extensions?.let { error.extensions(it) } }
            .path(env.executionStepInfo.path)
            .location(env.field.sourceLocation)
            .build()
    }

    private fun intern(
        ex: Throwable,
        melding: String,
        code: String,
    ): Uitkomst {
        val correlatieId = UUID.randomUUID().toString()
        log.warn { "Interne fout bij een GraphQL-aanroep ($code, ${ex::class.simpleName}: ${ex.message}), referentie $correlatieId" }
        return Uitkomst(melding, mapOf("code" to code, "correlatieId" to correlatieId))
    }

    private class Uitkomst(
        val melding: String?,
        val extensions: Map<String, Any>?,
    )

    private companion object {
        val log = KotlinLogging.logger {}
    }
}