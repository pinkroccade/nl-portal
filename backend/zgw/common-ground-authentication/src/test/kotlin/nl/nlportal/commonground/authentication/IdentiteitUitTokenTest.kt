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

import nl.nlportal.commonground.authentication.exception.UserTypeUnsupportedException
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/** De leesroute: de enige plek waar claims in een identiteit veranderen. */
internal class IdentiteitUitTokenTest {
    private val uuid = UUID.fromString("5d0e0a4e-3f4e-4b5b-9d1e-3b7a3f1d8a11")

    @Test
    fun `a token with a BSN yields a burger as vertegenwoordigde`() {
        val identiteit = IdentiteitUitToken.lees(JwtBuilder().vertegenwoordigdeBsn("1234").buildJwt())

        assertEquals(Vertegenwoordigde.Burger("1234"), identiteit.vertegenwoordigde)
    }

    @Test
    fun `a token with a KvK number yields a bedrijf without vestigingsnummer`() {
        val identiteit = IdentiteitUitToken.lees(JwtBuilder().vertegenwoordigdeKvk("1234").buildJwt())

        assertEquals(Vertegenwoordigde.Bedrijf("1234", null), identiteit.vertegenwoordigde)
    }

    @Test
    fun `a bedrijf carries its vestigingsnummer`() {
        val identiteit = IdentiteitUitToken.lees(JwtBuilder().vestigingsNummerKvk("1234", "000012345678").buildJwt())

        assertEquals(Vertegenwoordigde.Bedrijf("1234", "000012345678"), identiteit.vertegenwoordigde)
    }

    @Test
    fun `the gebruiker is the issuer and subject of the token`() {
        val jwt =
            JwtBuilder()
                .issuer("https://login.example.nl/realms/de-bosrand")
                .subject("subject-1")
                .vertegenwoordigdeBsn("1234")
                .buildJwt()

        assertEquals(Gebruiker("https://login.example.nl/realms/de-bosrand", "subject-1"), IdentiteitUitToken.lees(jwt).gebruiker)
    }

    @Test
    fun `machtigingsdienst uuids are read from a list or a single string, and absent without the claim`() {
        val list = JwtBuilder().vertegenwoordigdeKvk("1234").machtingDienstKvk(uuid.toString()).buildJwt()
        val none = JwtBuilder().vertegenwoordigdeKvk("1234").buildJwt()

        assertEquals(listOf(uuid), IdentiteitUitToken.lees(list).machtigingsdienstUuids)
        assertNull(IdentiteitUitToken.lees(none).machtigingsdienstUuids)
    }

    @Test
    fun `a token without BSN or KvK number is rejected`() {
        assertRejected(token().claim("random", "1234").build())
    }

    @Test
    fun `a token with only a subject is rejected, there is no uid fallback`() {
        assertRejected(token().build())
    }

    @Test
    fun `a BSN or KvK number outside the vertegenwoordigde claim is rejected, there is no fallback to the token itself`() {
        assertRejected(token().claim("bsn", "1234").build())
        assertRejected(token().claim("kvk", "1234").build())
    }

    @Test
    fun `a token without issuer or subject is rejected`() {
        val claim = mapOf("bsn" to "1234")
        assertRejected(
            Jwt
                .withTokenValue("t")
                .header("alg", "none")
                .subject("s")
                .claim("aanvrager", claim)
                .build(),
        )
        assertRejected(
            Jwt
                .withTokenValue("t")
                .header("alg", "none")
                .issuer("https://i.example")
                .claim("aanvrager", claim)
                .build(),
        )
    }

    private fun token() =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .issuer("https://i.example")
            .subject("s")

    private fun assertRejected(jwt: Jwt) {
        assertThrows<UserTypeUnsupportedException> { IdentiteitUitToken.lees(jwt) }
    }
}