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
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
}