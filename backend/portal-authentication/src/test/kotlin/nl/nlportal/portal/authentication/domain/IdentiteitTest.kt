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
package nl.nlportal.portal.authentication.domain

import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.security.authentication.AbstractAuthenticationToken

internal class IdentiteitTest {
    private val burger = Vertegenwoordigde.Burger("123456782")
    private val bedrijf = Vertegenwoordigde.Bedrijf("12345678", "000012345678")
    private val gebruiker = Gebruiker("https://login.example.nl/realms/portal", "subject-1", Inlogmiddel.DIGID, Betrouwbaarheidsniveau.SUBSTANTIEEL)

    @Test
    fun `the soort of a burger is bsn and of a bedrijf is kvk`() {
        assertEquals(Soort.BSN, burger.soort)
        assertEquals(Soort.KVK, bedrijf.soort)
        assertEquals("bsn", burger.soort.code)
        assertEquals("kvk", bedrijf.soort.code)
    }

    @Test
    fun `the representatie is the uppercase soort and the identificatie`() {
        assertEquals("BSN:123456782", burger.representatie)
        assertEquals("KVK:12345678", bedrijf.representatie)
    }

    @Test
    fun `the string form of an identity never contains the BSN or KvK number`() {
        val teksten =
            listOf(
                burger.toString(),
                bedrijf.toString(),
                Identiteit(gebruiker, burger).toString(),
                Identiteit(gebruiker, bedrijf).toString(),
            )

        teksten.forEach {
            assertFalse(it.contains("123456782"), it)
            assertFalse(it.contains("12345678"), it)
            assertFalse(it.contains("000012345678"), it)
        }
    }

    @Test
    fun `the string form of an identity still names the gebruiker`() {
        assertTrue(Identiteit(gebruiker, burger).toString().contains("subject-1"))
    }

    @Test
    fun `an authentication does not show its token or the identificatie in its string form`() {
        val authentication: AbstractAuthenticationToken = PortalAuthentication(Identiteit(gebruiker, burger), emptyList())

        assertDoesNotThrow { authentication.toString() }
        assertFalse(authentication.toString().contains("geheim.token.waarde"))
        assertFalse(authentication.toString().contains("123456782"))
    }

    @Test
    fun `an authentication carries no credentials`() {
        val authentication = PortalAuthentication(Identiteit(gebruiker, burger), emptyList())

        assertNull(authentication.credentials)
    }

    @Test
    fun `a bedrijf without vestigingsnummer says so in its string form and has no vestiging`() {
        val zonderVestiging = Vertegenwoordigde.Bedrijf("12345678")

        assertNull(zonderVestiging.vestigingsnummer)
        assertEquals("Bedrijf(kvkNummer=***, vestigingsnummer=geen)", zonderVestiging.toString())
        assertEquals("Bedrijf(kvkNummer=***, vestigingsnummer=***)", bedrijf.toString())
    }

    @Test
    fun `the user type of an authentication follows the vertegenwoordigde, and the principal the gebruiker`() {
        val burgerAuthentication = PortalAuthentication(Identiteit(gebruiker, burger), emptyList())
        val bedrijfAuthentication = PortalAuthentication(Identiteit(gebruiker, bedrijf), emptyList())

        assertEquals("bsn", burgerAuthentication.userType)
        assertEquals("kvk", bedrijfAuthentication.userType)
        assertEquals("subject-1", burgerAuthentication.principal)
    }
}