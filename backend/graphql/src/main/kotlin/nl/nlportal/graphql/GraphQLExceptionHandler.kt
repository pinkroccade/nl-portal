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

/**
 * Zet een fout van een data fetcher om in een GraphQL-fout. Wat de browser te zien krijgt staat op één plek, in [afhandeling]:
 *
 * | Fout | \`extensions.code\` | Melding naar de browser |
 * |---|---|---|
 * | [ModuleNotAvailableException] (module-flag uit) | \`MODULE_NIET_BESCHIKBAAR\` (met \`module\`) | de melding van de fout |
 * | [ModuleNotConfiguredException] (geen moduleconfiguratie bij de tenant) | \`MODULE_NIET_GECONFIGUREERD\` | vaste tekst |
 * | [TenantContextMissingException] (geen tenant bij het verzoek) | \`TENANT_ONTBREEKT\` | vaste tekst |
 * | [KoppelingException] (de bron faalt) | \`KOPPELING_FOUT\` (met \`correlatieId\`) | de generieke melding van de fout |
 * | overige | geen | de melding van de fout |
 *
 * De meldingen van [ModuleNotConfiguredException] en [TenantContextMissingException] bevatten de OIN of de naam van een instelling:
 * die gaan alleen naar de log, nooit naar de browser. Codes zijn Nederlands en in hoofdletters; een nieuw type komt hier bij, op één plek.
 */
class GraphQLExceptionHandler : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? {
        val afhandeling = afhandeling(ex)
        if (afhandeling?.detailsNaarLog == true) {
            log.warn { "GraphQL-fout met configuratiedetails (alleen in de log): ${ex.message}" }
        }
        return GraphqlErrorBuilder
            .newError()
            .message(afhandeling?.melding ?: ex.message)
            .also { error -> afhandeling?.let { error.extensions(it.extensions) } }
            .path(env.executionStepInfo.path)
            .location(env.field.sourceLocation)
            .build()
    }

    /** Wat de browser van deze fout krijgt, of `null` voor een fout zonder eigen afhandeling. */
    private fun afhandeling(ex: Throwable): Afhandeling? =
        when (ex) {
            is ModuleNotAvailableException -> {
                Afhandeling(extensions = mapOf("code" to "MODULE_NIET_BESCHIKBAAR", "module" to ex.module))
            }

            is ModuleNotConfiguredException -> {
                Afhandeling("De module is niet beschikbaar", mapOf("code" to "MODULE_NIET_GECONFIGUREERD"), detailsNaarLog = true)
            }

            is TenantContextMissingException -> {
                Afhandeling("De aanvraag kan niet worden verwerkt", mapOf("code" to "TENANT_ONTBREEKT"), detailsNaarLog = true)
            }

            // Foutdetails van een bron gaan niet naar de browser: alleen een generieke code en de referentie voor de log.
            is KoppelingException -> {
                Afhandeling(extensions = mapOf("code" to "KOPPELING_FOUT", "correlatieId" to ex.correlatieId))
            }

            else -> {
                null
            }
        }

    /** [melding] is `null` als de melding van de fout zelf naar de browser mag; [detailsNaarLog]: de melding van de fout gaat alleen naar de log. */
    private class Afhandeling(
        val melding: String? = null,
        val extensions: Map<String, Any>,
        val detailsNaarLog: Boolean = false,
    )

    private companion object {
        val log = KotlinLogging.logger {}
    }
}