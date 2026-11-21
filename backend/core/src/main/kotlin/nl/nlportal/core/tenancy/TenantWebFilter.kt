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
package nl.nlportal.core.tenancy

import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Bepaalt de tenant uit de host van het request en schrijft die met `contextWrite` in de Reactor Context. Draait
 * vóór de security-filterketen, zodat ook de paden onder oauth2, login en logout een tenant hebben. Een onbekende
 * host krijgt een kale 404: geen standaardtenant en geen tenantinformatie in het antwoord.
 *
 * Hostregels: zonder vertrouwde proxy telt de enige `Host`-header; van een vertrouwde proxy (zie [TrustedProxies])
 * gaat `Forwarded: host=` voor `X-Forwarded-Host`, en telt bij meerdere waarden de laatste, die de proxy zelf toevoegde.
 * De host wordt genormaliseerd en exact tegen de allowlist gezocht.
 */
class TenantWebFilter(
    private val directory: TenantDirectory,
    private val trustedProxies: TrustedProxies,
) : WebFilter,
    Ordered {
    override fun getOrder() = ORDER

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        if (draaitOpManagementpoort(exchange)) return chain.filter(exchange)
        val tenant =
            hostVan(exchange.request)
                ?.let(TenantDirectory::normaliseerHost)
                ?.let(directory::forHost)
        if (tenant == null) {
            exchange.response.statusCode = HttpStatus.NOT_FOUND
            return exchange.response.setComplete()
        }
        exchange.attributes[ATTRIBUUT] = tenant.oin
        return chain.filter(exchange).contextWrite { it.put(TenantContext.SLEUTEL, tenant.oin) }
    }

    /** De managementpoort is een kindcontext die de filters van de hoofdcontext ziet; daar hoort geen tenantresolutie. */
    private fun draaitOpManagementpoort(exchange: ServerWebExchange): Boolean = exchange.applicationContext?.let { WebServerApplicationContext.hasServerNamespace(it, "management") } ?: false

    private fun hostVan(request: ServerHttpRequest): String? {
        if (trustedProxies.trusts(request.remoteAddress)) {
            forwardedHost(request)?.let { return it }
        }
        return request.headers["Host"]?.singleOrNull()
    }

    /** `null`: geen forwarded host aangegeven. Een lege of onbruikbare waarde is wel aangegeven en valt dus niet terug op `Host`. */
    private fun forwardedHost(request: ServerHttpRequest): String? {
        request.headers["Forwarded"]?.let { waarden ->
            val laatste = waarden.joinToString(",").split(',').last()
            val host =
                laatste
                    .split(';')
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("host=", ignoreCase = true) }
                    ?.substring("host=".length)
                    ?.trim()
                    ?.removeSurrounding("\"")
            if (host != null) return host
        }
        request.headers["X-Forwarded-Host"]?.let { waarden ->
            return waarden
                .joinToString(",")
                .split(',')
                .last()
                .trim()
        }
        return null
    }

    companion object {
        /** Vóór `WebFilterChainProxy` van Spring Security (-100). */
        const val ORDER = -200

        /** Attribuut op de exchange met de [Oin], voor wie buiten de Reactor Context moet lezen (bijvoorbeeld observability). */
        const val ATTRIBUUT = "nl.nlportal.core.tenancy.Oin"
    }
}