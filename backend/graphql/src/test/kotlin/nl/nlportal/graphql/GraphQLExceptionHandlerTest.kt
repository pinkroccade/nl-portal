/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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

import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.koppeling.KoppelingProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.BadCredentialsException

class GraphQLExceptionHandlerTest {
    private val env: DataFetchingEnvironment =
        mock(DataFetchingEnvironment::class.java, RETURNS_DEEP_STUBS).also {
            `when`(it.field.sourceLocation).thenReturn(SourceLocation(1, 1))
        }

    @Test
    fun `een fout van een koppeling toont de browser alleen een generieke melding en de referentie, nooit de details van de bron`() {
        val fout =
            KoppelingException(
                koppeling = "brp",
                soort = KoppelingFoutsoort.BRON_PROBLEEM,
                problem = KoppelingProblem(title = "Persoon 999993653 niet gevonden", status = 404, detail = "BSN 999993653 onbekend"),
            )

        val graphqlFout = GraphQLExceptionHandler().resolveException(fout, env).block()!!.single()

        assertEquals("Koppeling 'brp' is niet beschikbaar (referentie ${fout.correlatieId})", graphqlFout.message)
        assertEquals(mapOf("code" to "KOPPELING_FOUT", "correlatieId" to fout.correlatieId), graphqlFout.extensions)
        assertFalse(graphqlFout.toSpecification().toString().contains("999993653"))
    }

    @Test
    fun `een ontbrekende identiteit krijgt de code UNAUTHENTICATED en een melding zonder detail`() {
        val fout = AuthenticationCredentialsNotFoundException("An Authentication object was not found; sessie 4f3a van 999993653")

        val graphqlFout = GraphQLExceptionHandler().resolveException(fout, env).block()!!.single()

        assertEquals("Niet geauthenticeerd", graphqlFout.message)
        assertEquals(mapOf("code" to "UNAUTHENTICATED"), graphqlFout.extensions)
        assertFalse(graphqlFout.toSpecification().toString().contains("999993653"))
    }

    @Test
    fun `elke andere authenticatiefout krijgt dezelfde code`() {
        val graphqlFout = GraphQLExceptionHandler().resolveException(BadCredentialsException("geheim detail"), env).block()!!.single()

        assertEquals("UNAUTHENTICATED", graphqlFout.extensions["code"])
        assertFalse(graphqlFout.message.contains("geheim"))
    }
}