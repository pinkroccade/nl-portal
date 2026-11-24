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
package nl.nlportal.commonground.authentication

import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

internal class JwtBuilderTest {
    @Test
    fun `a burger authentication carries the BSN as vertegenwoordigde`() {
        val authentication = JwtBuilder().vertegenwoordigdeBsn("1234").buildAuthentication()

        assertEquals(Vertegenwoordigde.Burger("1234"), authentication.identiteit.vertegenwoordigde)
    }

    @Test
    fun `a bedrijf authentication carries the KvK number as vertegenwoordigde`() {
        val authentication = JwtBuilder().vertegenwoordigdeKvk("1234").buildAuthentication()

        assertEquals(Vertegenwoordigde.Bedrijf("1234"), authentication.identiteit.vertegenwoordigde)
    }

    @Test
    fun `building throws when no bsn or kvk has been set`() {
        assertThrows<IllegalStateException> { JwtBuilder().buildAuthentication() }
    }

    @Test
    fun `vertegenwoordigdeKvk throws when bsn has already been set`() {
        assertThrows<AssertionError> { JwtBuilder().vertegenwoordigdeBsn("123").vertegenwoordigdeKvk("456") }
    }

    @Test
    fun `vertegenwoordigdeBsn throws when kvk has already been set`() {
        assertThrows<AssertionError> { JwtBuilder().vertegenwoordigdeKvk("456").vertegenwoordigdeBsn("123") }
    }

    @Test
    fun `issuer and subject end up in the identiteit as gebruiker with the test login method`() {
        val authentication =
            JwtBuilder()
                .issuer("https://andere.example.nl/realms/x")
                .subject("sub-42")
                .vertegenwoordigdeBsn("1234")
                .buildAuthentication()

        assertEquals(
            Gebruiker("https://andere.example.nl/realms/x", "sub-42", Inlogmiddel.GEBRUIKERSNAAM, Betrouwbaarheidsniveau.BASIS),
            authentication.identiteit.gebruiker,
        )
    }

    @Test
    fun `a vestigingsnummer is carried along with the KvK number`() {
        val authentication = JwtBuilder().vestigingsNummerKvk("1234", "000012345678").buildAuthentication()

        assertEquals(Vertegenwoordigde.Bedrijf("1234", "000012345678"), authentication.identiteit.vertegenwoordigde)
    }

    @Test
    fun `a machtigingsdienst is carried along as uuid`() {
        val uuid = UUID.randomUUID()

        val authentication = JwtBuilder().vertegenwoordigdeKvk("1234").machtingDienstKvk(uuid.toString()).buildAuthentication()

        assertEquals(listOf(uuid), authentication.identiteit.machtigingsdienstUuids)
    }
}