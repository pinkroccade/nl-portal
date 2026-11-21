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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.util.UUID

/** Wat een machtigingsdienst in het token een bedrijf toestaat; een burger heeft geen beperkingen. */
internal class AuthenticationMachtigingsDienstServiceTest {
    private val dienst = UUID.fromString("5d0e0a4e-3f4e-4b5b-9d1e-3b7a3f1d8a11")
    private val alleMachtigingen = UUID.fromString("6e1f1b5f-4a5f-4c6c-8e2f-4c8b4a2e9b22")
    private val zaaktype = UUID.fromString("7f202c60-5b60-4d7d-9f30-5d9c5b3fac33")
    private val anderZaaktype = UUID.fromString("80313d71-6c71-4e8e-a041-6ead6c40bd44")
    private val producttype = UUID.fromString("91424e82-7d82-4f9f-b152-7fbe7d51ce55")
    private val anderProducttype = UUID.fromString("a2535f93-8e93-40a0-8263-80cf8e62df66")

    private val service =
        AuthenticationMachtigingsDienstService(
            AuthenticationMachtigingsDienstConfig(
                allMachtigingUuid = alleMachtigingen,
                configurations =
                    listOf(
                        AuthenticationMachtigingsDienst(
                            uuid = dienst,
                            naam = "dienst",
                            zaakTypes = listOf(zaaktype),
                            productTypes = listOf(producttype),
                            taakTypes = listOf("toegestaan"),
                        ),
                    ),
            ),
            DefaultResourceLoader(),
        )

    private fun bedrijf(vararg diensten: UUID) =
        JwtBuilder()
            .vertegenwoordigdeKvk("12345678")
            .apply { diensten.forEach { machtingDienstKvk(it.toString()) } }
            .buildAuthentication()

    private val bedrijfMetDienst get() = bedrijf(dienst)
    private val bedrijfZonderDienst get() = bedrijf()
    private val burgerMetDienst =
        JwtBuilder().vertegenwoordigdeBsn("123456782").machtingDienstKvk(dienst.toString()).buildAuthentication()

    @Test
    fun `a bedrijf with a machtigingsdienst gets the types of that dienst`() {
        assertTrue(service.hasMachtingsDienst(bedrijfMetDienst))
        assertEquals(listOf(zaaktype), service.zaakTypes(bedrijfMetDienst))
        assertEquals(listOf("toegestaan"), service.taakTypes(bedrijfMetDienst))
        assertEquals(listOf(producttype), service.productTypes(bedrijfMetDienst))
    }

    @Test
    fun `a bedrijf without a machtigingsdienst has no restrictions`() {
        assertFalse(service.hasMachtingsDienst(bedrijfZonderDienst))
        assertNull(service.zaakTypes(bedrijfZonderDienst))
        assertNull(service.taakTypes(bedrijfZonderDienst))
        assertNull(service.productTypes(bedrijfZonderDienst))
    }

    @Test
    fun `the dienst that grants all machtigingen counts as no machtigingsdienst`() {
        val authentication = bedrijf(alleMachtigingen)

        assertFalse(service.hasMachtingsDienst(authentication))
        assertNull(service.zaakTypes(authentication))
    }

    @Test
    fun `an unknown machtigingsdienst yields no types`() {
        val authentication = bedrijf(UUID.randomUUID())

        assertNull(service.zaakTypes(authentication))
        assertNull(service.taakTypes(authentication))
        assertNull(service.productTypes(authentication))
        assertNull(service.getAuthenticationMachtingsDienst(null))
    }

    @Test
    fun `a burger is never restricted by a machtigingsdienst`() {
        assertNull(service.zaakTypes(burgerMetDienst))
        assertNull(service.taakTypes(burgerMetDienst))
        assertNull(service.productTypes(burgerMetDienst))
        assertTrue(service.isAllowedZaakType(burgerMetDienst, anderZaaktype))
        assertTrue(service.isAllowedZaakTypes(burgerMetDienst, listOf(anderZaaktype)))
        assertTrue(service.isAllowedTaakType(burgerMetDienst, "elders"))
        assertTrue(service.isAllowedProductType(burgerMetDienst, anderProducttype))
        assertEquals(listOf(anderProducttype), service.filterAllowedProductTypes(burgerMetDienst, listOf(anderProducttype)))
    }

    @Test
    fun `a bedrijf with a dienst may only use the zaaktypes of that dienst`() {
        assertTrue(service.isAllowedZaakType(bedrijfMetDienst, zaaktype))
        assertFalse(service.isAllowedZaakType(bedrijfMetDienst, anderZaaktype))
        assertTrue(service.isAllowedZaakTypes(bedrijfMetDienst, listOf(anderZaaktype, zaaktype)))
        assertFalse(service.isAllowedZaakTypes(bedrijfMetDienst, listOf(anderZaaktype)))
    }

    @Test
    fun `a bedrijf with a dienst may only use the taaktypes of that dienst`() {
        assertTrue(service.isAllowedTaakType(bedrijfMetDienst, "toegestaan"))
        assertFalse(service.isAllowedTaakType(bedrijfMetDienst, "verboden"))
    }

    @Test
    fun `a bedrijf with a dienst may only use the producttypes of that dienst`() {
        assertTrue(service.isAllowedProductType(bedrijfMetDienst, producttype))
        assertFalse(service.isAllowedProductType(bedrijfMetDienst, anderProducttype))
        assertEquals(listOf(producttype), service.filterAllowedProductTypes(bedrijfMetDienst, listOf(producttype, anderProducttype)))
        assertEquals(listOf(producttype), service.filterAllowedProductTypes(bedrijfMetDienst, null))
        assertTrue(service.isAllowedProductTypes(bedrijfMetDienst, listOf(producttype)))
        assertFalse(service.isAllowedProductTypes(bedrijfMetDienst, listOf(anderProducttype)))
    }

    @Test
    fun `a bedrijf without a dienst may use any type`() {
        assertTrue(service.isAllowedZaakType(bedrijfZonderDienst, anderZaaktype))
        assertTrue(service.isAllowedZaakTypes(bedrijfZonderDienst, listOf(anderZaaktype)))
        assertTrue(service.isAllowedTaakType(bedrijfZonderDienst, "elders"))
        assertTrue(service.isAllowedProductType(bedrijfZonderDienst, anderProducttype))
        assertEquals(emptyList<UUID>(), service.filterAllowedProductTypes(bedrijfZonderDienst, null))
    }
}