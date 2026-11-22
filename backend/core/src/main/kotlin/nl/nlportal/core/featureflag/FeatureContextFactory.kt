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
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.Value
import nl.nlportal.core.tenancy.GeheimVerwijzing
import nl.nlportal.core.tenancy.Oin
import org.apache.commons.codec.digest.HmacAlgorithms
import org.apache.commons.codec.digest.HmacUtils
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono

/**
 * Builds the OpenFeature evaluation context for a request. The context is passed explicitly to every
 * evaluation; it is never stored in thread-bound state, which does not survive thread hops in WebFlux.
 *
 * The context never holds personal data: only tenant, environment, user type and an opaque user id,
 * a keyed HMAC of the identity provider subject with a per-tenant secret.
 *
 * With an [Oin] the context is that of one tenant: the `tenant` attribute is the OIN and the user id is keyed with the
 * secret of that tenant ([tenantSleutel]), never with a key of another tenant or a global one. A tenant without a key
 * gets no user id. Without an OIN (single-tenant runtime) the global [FeatureFlagProperties.Context.userIdSecret] applies.
 */
class FeatureContextFactory(
    private val properties: FeatureFlagProperties.Context,
    private val tenantSleutel: (Oin) -> String? = { null },
) {
    /** The context of the current request, read from the reactive security context. */
    fun current(): Mono<EvaluationContext> =
        ReactiveSecurityContextHolder
            .getContext()
            .mapNotNull { it.authentication }
            .map { create(it) }
            .switchIfEmpty(Mono.fromSupplier { create(null) })

    fun create(authentication: Authentication?): EvaluationContext = create(authentication, null)

    fun create(
        authentication: Authentication?,
        oin: Oin?,
    ): EvaluationContext {
        val user = authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        val attributes =
            buildMap {
                put(TENANT, Value(oin?.value ?: properties.tenant))
                properties.environment?.let { put(ENVIRONMENT, Value(it)) }
                (user as? UserTypeAware)?.let { put(USER_TYPE, Value(it.userType)) }
            }
        return ImmutableContext(opaqueUserId(user, oin), attributes)
    }

    private fun opaqueUserId(
        user: Authentication?,
        oin: Oin?,
    ): String? {
        val secret = (if (oin != null) tenantSleutel(oin) else properties.userIdSecret)?.takeIf { it.isNotBlank() } ?: return null
        val subject = (user as? UserTypeAware)?.subject ?: return null
        return HmacUtils(HmacAlgorithms.HMAC_SHA_256, secret).hmacHex(subject)
    }

    companion object {
        const val TENANT = "tenant"
        const val ENVIRONMENT = "environment"
        const val USER_TYPE = "userType"

        /** De verwijzing naar de HMAC-sleutel van [oin] in de secret store; valt onder de OIN-naamruimte van de tenant. */
        fun sleutelVerwijzing(oin: Oin) = GeheimVerwijzing("${oin.value}.evaluatiecontext-hmac")
    }
}