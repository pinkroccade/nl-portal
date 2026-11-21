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
package nl.nlportal.commonground.authentication

import nl.nlportal.commonground.authentication.IdentiteitUitToken.BSN_CLAIM
import nl.nlportal.commonground.authentication.IdentiteitUitToken.KVK_CLAIM
import nl.nlportal.commonground.authentication.IdentiteitUitToken.VERTEGENWOORDIGDE_CLAIM
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `the jwt has the BSN under the vertegenwoordigde claim`() {
        val jwt = JwtBuilder().vertegenwoordigdeBsn("1234").buildJwt()

        assertEquals("1234", jwt.getClaim<Map<String, Any>>(VERTEGENWOORDIGDE_CLAIM)!![BSN_CLAIM])
    }

    @Test
    fun `the jwt has the KvK number under the vertegenwoordigde claim`() {
        val jwt = JwtBuilder().vertegenwoordigdeKvk("1234").buildJwt()

        assertEquals("1234", jwt.getClaim<Map<String, Any>>(VERTEGENWOORDIGDE_CLAIM)!![KVK_CLAIM])
    }

    @Test
    fun `building throws when no bsn or kvk has been set`() {
        assertThrows<IllegalStateException> { JwtBuilder().buildJwt() }
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
    fun `issuer and subject end up in the identiteit as gebruiker`() {
        val authentication =
            JwtBuilder()
                .issuer("https://andere.example.nl/realms/x")
                .subject("sub-42")
                .vertegenwoordigdeBsn("1234")
                .buildAuthentication()

        assertEquals(Gebruiker("https://andere.example.nl/realms/x", "sub-42"), authentication.identiteit.gebruiker)
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

    @Test
    fun `the bedrijf annotation builds a bedrijf authentication with vestiging and machtigingsdienst`() {
        val annotation = BedrijfAnnotated::class.java.getAnnotation(WithBedrijfUser::class.java)

        val authentication =
            WithBedrijfUserSecurityContextFactory().createSecurityContext(annotation).authentication as PortalAuthentication

        assertEquals(Vertegenwoordigde.Bedrijf("1234", "000012345678"), authentication.identiteit.vertegenwoordigde)
        assertEquals(listOf(UUID.fromString(MACHTIGING)), authentication.identiteit.machtigingsdienstUuids)
    }

    @Test
    fun `the bedrijf annotation without vestiging and machtigingsdienst builds a plain bedrijf`() {
        val annotation = PlainBedrijfAnnotated::class.java.getAnnotation(WithBedrijfUser::class.java)

        val authentication =
            WithBedrijfUserSecurityContextFactory().createSecurityContext(annotation).authentication as PortalAuthentication

        assertEquals(Vertegenwoordigde.Bedrijf("1234"), authentication.identiteit.vertegenwoordigde)
        assertNull(authentication.identiteit.machtigingsdienstUuids)
    }

    @Test
    fun `the burger annotation builds a burger authentication`() {
        val annotation = BurgerAnnotated::class.java.getAnnotation(WithBurgerUser::class.java)

        val authentication =
            WithBurgerUserSecurityContextFactory().createSecurityContext(annotation).authentication as PortalAuthentication

        assertEquals(Vertegenwoordigde.Burger("999990001"), authentication.identiteit.vertegenwoordigde)
    }

    @WithBedrijfUser("1234", vestigingsNummer = "000012345678", machtigingsDienst = MACHTIGING)
    private class BedrijfAnnotated

    @WithBedrijfUser("1234")
    private class PlainBedrijfAnnotated

    @WithBurgerUser("999990001")
    private class BurgerAnnotated

    private companion object {
        const val MACHTIGING = "11111111-2222-4333-8444-555555555555"
    }
}