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

import dev.openfeature.sdk.EvaluationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.testing.TestTenants
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

class FeatureContextFactoryTest {
    private val factory =
        FeatureContextFactory(
            FeatureFlagProperties.Context().apply {
                environment = "test"
            },
        )

    /** A logged-in citizen as the BFF builds it from the session: a subject and a user type, no token. */
    private class CitizenAuthentication(
        private val subject: String,
    ) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_USER"))),
        UserTypeAware {
        init {
            super.setAuthenticated(true)
        }

        override val userType = "bsn"

        override fun getCredentials(): Any? = null

        override fun getPrincipal(): Any = subject
    }

    private val oin = TestTenants.BOSRAND
    private val citizen = CitizenAuthentication(SUBJECT)

    @Test
    fun `an authenticated request carries tenant, environment and user type`() {
        val context = factory.create(oin, citizen)

        assertEquals(oin.value, context.getValue("tenant").asString())
        assertEquals("test", context.getValue("environment").asString())
        assertEquals("bsn", context.getValue("userType").asString())
    }

    @Test
    fun `the context never holds a user id, only tenant, environment and user type`() {
        val context = factory.create(oin, citizen)

        assertNull(context.targetingKey)
        assertEquals(setOf("tenant", "environment", "userType"), context.keySet())
        assertFalse(allValues(context).any { it.contains(SUBJECT) }, "subject found in evaluation context")
    }

    @Test
    fun `an anonymous or unauthenticated authentication yields no user attributes`() {
        val anonymous = AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        val unauthenticated = CitizenAuthentication(SUBJECT).apply { isAuthenticated = false }

        listOf(anonymous, unauthenticated).forEach {
            val context = factory.create(oin, it)
            assertNull(context.targetingKey)
            assertNull(context.getValue("userType"))
        }
    }

    @Test
    fun `an anonymous request carries tenant and environment only`() {
        val context = factory.create(oin)

        assertNull(context.targetingKey)
        assertNull(context.getValue("userType"))
        assertEquals(oin.value, context.getValue("tenant").asString())
    }

    @Test
    fun `the current context follows the reactive security context across threads`() {
        val context =
            factory
                .current()
                .publishOn(Schedulers.boundedElastic())
                .flatMap { first -> factory.current().map { second -> first to second } }
                .subscribeOn(Schedulers.parallel())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(citizen))
                .contextWrite(TestTenants.tenantContext(oin))
                .block()!!

        assertEquals("bsn", context.first.getValue("userType").asString())
        assertEquals(context.first.asObjectMap(), context.second.asObjectMap())
    }

    @Test
    fun `the current context is anonymous outside an authenticated request`() {
        val context = Mono.defer { factory.current() }.contextWrite(TestTenants.tenantContext(oin)).block()!!

        assertNull(context.targetingKey)
        assertEquals(oin.value, context.getValue("tenant").asString())
    }

    @Test
    fun `without a tenant in the context there is no evaluation context`() {
        assertThrows(TenantContextMissingException::class.java) { Mono.defer { factory.current() }.block() }
    }

    private fun allValues(context: EvaluationContext): List<String> = listOfNotNull(context.targetingKey) + context.asObjectMap().values.map { it.toString() }

    private companion object {
        const val SUBJECT = "c0ffee00-0000-4000-8000-000000000001"
    }
}