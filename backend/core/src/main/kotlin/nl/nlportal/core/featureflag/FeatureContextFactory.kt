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
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext

/**
 * Builds the OpenFeature evaluation context for a request. The context is passed explicitly to every
 * evaluation; it is never stored in thread-bound state, which does not survive thread hops in WebFlux.
 *
 * The context never holds personal data and no user id (no targeting key): only tenant, environment and
 * user type. Flags decide per tenant, not per citizen (ADR 0020). The tenant is the OIN of the tenant of the request,
 * read from the [TenantContext]; without a tenant there is no context and the evaluation fails closed.
 */
class FeatureContextFactory(
    private val properties: FeatureFlagProperties.Context,
) {
    /** The context of the current request: tenant from the [TenantContext], user type from the reactive security context. */
    fun current(): Mono<EvaluationContext> =
        TenantContext.currentMono().flatMap { oin ->
            ReactiveSecurityContextHolder
                .getContext()
                .mapNotNull { it.authentication }
                .map { create(oin, it) }
                .switchIfEmpty(Mono.fromSupplier { create(oin, null) })
        }

    fun create(
        oin: Oin,
        authentication: Authentication? = null,
    ): EvaluationContext {
        val user = authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        val attributes =
            buildMap {
                put(TENANT, Value(oin.value))
                properties.environment?.let { put(ENVIRONMENT, Value(it)) }
                (user as? UserTypeAware)?.let { put(USER_TYPE, Value(it.userType)) }
            }
        return ImmutableContext(attributes)
    }

    companion object {
        const val TENANT = "tenant"
        const val ENVIRONMENT = "environment"
        const val USER_TYPE = "userType"
    }
}