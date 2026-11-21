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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import nl.nlportal.core.koppeling.KoppelingConfiguratieException
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.koppeling.KoppelingProblem
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.util.ModuleNotConfiguredException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory

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
    fun `een module die voor de tenant niet is ingesteld toont de browser geen configuratiedetails zoals de OIN`() {
        val fout = ModuleNotConfiguredException("BrpConfiguratie", "Tenant Oin(value=00000099000000000001): modulegegevens")

        val graphqlFout = GraphQLExceptionHandler().resolveException(fout, env).block()!!.single()

        assertEquals("Module is niet beschikbaar", graphqlFout.message)
        assertEquals("MODULE_NIET_GECONFIGUREERD", graphqlFout.extensions["code"])
        assertNotNull(graphqlFout.extensions["correlatieId"])
        assertFalse(graphqlFout.toSpecification().toString().contains("00000099000000000001"))
    }

    @Test
    fun `een request zonder tenant toont de browser een generieke melding`() {
        val graphqlFout = GraphQLExceptionHandler().resolveException(TenantContextMissingException(), env).block()!!.single()

        assertEquals("Niet geautoriseerd", graphqlFout.message)
        assertEquals("TENANT_ONTBREEKT", graphqlFout.extensions["code"])
        assertNotNull(graphqlFout.extensions["correlatieId"])
    }

    @Test
    fun `een configuratiefout van een koppeling toont de browser een generieke melding zonder headernaam`() {
        val fout = KoppelingConfiguratieException("Header 'X-Doelbinding-Geheim' hoort bij de koppeling")

        val graphqlFout = GraphQLExceptionHandler().resolveException(fout, env).block()!!.single()

        assertEquals("Koppeling is niet beschikbaar", graphqlFout.message)
        assertEquals("KOPPELING_FOUT", graphqlFout.extensions["code"])
        assertNotNull(graphqlFout.extensions["correlatieId"])
    }

    @Test
    fun `de log van een interne fout noemt code, klassenaam, melding en correlatieId, zonder stacktrace, en de browser krijgt de melding niet`() {
        val logger = LoggerFactory.getLogger(GraphQLExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            val fout = KoppelingConfiguratieException("Header X-Doelbinding hoort bij de koppeling")

            val graphqlFout = GraphQLExceptionHandler().resolveException(fout, env).block()!!.single()

            val regel = appender.list.single()
            val correlatieId = graphqlFout.extensions["correlatieId"] as String
            val tekst = regel.formattedMessage
            assertTrue(tekst.contains("KOPPELING_FOUT"))
            assertTrue(tekst.contains("KoppelingConfiguratieException"))
            assertTrue(tekst.contains("Header X-Doelbinding hoort bij de koppeling"))
            assertTrue(tekst.contains(correlatieId))
            assertEquals(null, regel.throwableProxy)
            assertFalse(graphqlFout.toSpecification().toString().contains("X-Doelbinding"))
        } finally {
            logger.detachAppender(appender)
        }
    }
}