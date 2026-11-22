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
package nl.nlportal.core.featureflag

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/** Seam: de evaluatiecontext per tenant (OIN) en de HMAC-sleutel per tenant (ADR 0020 punt 4, ADR 0021). */
class FeatureContextFactoryTenantTest {
    private val bosrand = Oin("00000099000000000001")
    private val wijdeVeld = Oin("00000099000000000002")

    private class Burger(
        override val subject: String,
    ) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_USER"))),
        UserTypeAware {
        init {
            super.setAuthenticated(true)
        }

        override val userType = "bsn"

        override fun getCredentials(): Any? = null

        override fun getPrincipal(): Any = subject
    }

    private val sleutels = mapOf(bosrand to "sleutel-bosrand", wijdeVeld to "sleutel-wijdeveld")

    private val factory =
        FeatureContextFactory(FeatureFlagProperties.Context().apply { userIdSecret = "globaal" }) { sleutels[it] }

    @Test
    fun `de OIN is het tenantattribuut van de context`() {
        assertEquals(bosrand.value, factory.create(null, bosrand).getValue("tenant").asString())
        assertEquals("default", factory.create(null).getValue("tenant").asString())
    }

    @Test
    fun `het gebruikers-id is een HMAC met de sleutel van de eigen tenant, nooit met een andere of de globale`() {
        val burger = Burger("subject-1")

        val inBosrand = factory.create(burger, bosrand).targetingKey
        val inWijdeVeld = factory.create(burger, wijdeVeld).targetingKey
        val globaal = factory.create(burger).targetingKey

        assertNotEquals(inBosrand, inWijdeVeld)
        assertNotEquals(inBosrand, globaal)
        assertEquals(inBosrand, factory.create(burger, bosrand).targetingKey)
    }

    @Test
    fun `een tenant zonder sleutel krijgt geen gebruikers-id, ook niet met een globale sleutel`() {
        val zonder = FeatureContextFactory(FeatureFlagProperties.Context().apply { userIdSecret = "globaal" }) { null }

        assertNull(zonder.create(Burger("subject-1"), bosrand).targetingKey)
        assertNull(FeatureContextFactory(FeatureFlagProperties.Context()) { "  " }.create(Burger("subject-1"), bosrand).targetingKey)
    }

    @Test
    fun `de sleutelverwijzing valt onder de OIN-naamruimte van de tenant`() {
        val verwijzing = FeatureContextFactory.sleutelVerwijzing(bosrand)

        assertTrue(verwijzing.valtOnder(bosrand.value))
        assertEquals("00000099000000000001.evaluatiecontext-hmac", verwijzing.naam)
    }

    @Test
    fun `de autoconfiguratie haalt de sleutel per tenant uit de geheimenbron`() {
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton(
            "geheimen",
            GeheimenBron { if (it == FeatureContextFactory.sleutelVerwijzing(bosrand)) "uit-geheimenbron" else null },
        )
        val metBron = FeatureFlagAutoConfiguration().featureContextFactory(FeatureFlagProperties(), beans.getBeanProvider(GeheimenBron::class.java))
        val zonderBron = FeatureFlagAutoConfiguration().featureContextFactory(FeatureFlagProperties(), DefaultListableBeanFactory().getBeanProvider(GeheimenBron::class.java))
        val expected = FeatureContextFactory(FeatureFlagProperties.Context()) { "uit-geheimenbron" }.create(Burger("s"), bosrand).targetingKey

        assertEquals(expected, metBron.create(Burger("s"), bosrand).targetingKey)
        assertNull(metBron.create(Burger("s"), wijdeVeld).targetingKey)
        assertNull(zonderBron.create(Burger("s"), bosrand).targetingKey)
    }
}