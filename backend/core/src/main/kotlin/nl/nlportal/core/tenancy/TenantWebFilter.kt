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
        return chain.filter(exchange).contextWrite { OinMdcBridge.schrijf(it.put(TenantContext.SLEUTEL, tenant.oin), tenant.oin) }
    }

    /** De managementpoort is een kindcontext die de filters van de hoofdcontext ziet; daar hoort geen tenantresolutie. */
    private fun draaitOpManagementpoort(exchange: ServerWebExchange): Boolean = exchange.applicationContext?.let { WebServerApplicationContext.hasServerNamespace(it, "management") } ?: false

    private fun hostVan(request: ServerHttpRequest): String? {
        if (trustedProxies.trusts(request.remoteAddress)) {
            forwardedHost(request)?.let { return it }
        }
        val hosts = request.headers["Host"]
        return when {
            // Zonder Host-header telt alleen een in-memory request (geen afzenderadres, absolute URI) via de request-URI.
            // Een echte verbinding heeft altijd een afzenderadres: Reactor Netty vult `request.uri` dan zelf aan, bijvoorbeeld
            // met `localhost`, en dat mag geen tenant opleveren.
            hosts.isNullOrEmpty() -> if (request.remoteAddress == null) request.uri.authority else null

            hosts.size == 1 -> hosts.single()

            else -> null
        }
    }

    /**
     * `null`: geen forwarded host aangegeven. Een lege of onbruikbare waarde is wel aangegeven en valt dus niet terug op
     * `Host`; een `Forwarded` zonder `host=` is onbruikbaar. Staan `Forwarded` en `X-Forwarded-Host` er allebei en
     * spreken ze elkaar tegen, dan is de host onbruikbaar: een ingress overschrijft niet altijd beide headers.
     */
    private fun forwardedHost(request: ServerHttpRequest): String? {
        val forwarded = request.headers["Forwarded"]?.let { hostUitForwarded(it) ?: ONBRUIKBAAR }
        val xForwardedHost =
            request.headers["X-Forwarded-Host"]?.let { waarden ->
                waarden
                    .joinToString(",")
                    .split(',')
                    .last()
                    .trim()
            }
        return when {
            forwarded == null -> xForwardedHost
            xForwardedHost == null -> forwarded
            TenantDirectory.normaliseerHost(forwarded) == TenantDirectory.normaliseerHost(xForwardedHost) -> xForwardedHost
            else -> ONBRUIKBAAR
        }
    }

    private fun hostUitForwarded(waarden: List<String>): String? =
        waarden
            .joinToString(",")
            .split(',')
            .last()
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("host=", ignoreCase = true) }
            ?.substring("host=".length)
            ?.trim()
            ?.removeSurrounding("\"")

    companion object {
        /** Vóór `WebFilterChainProxy` van Spring Security (-100). */
        const val ORDER = -200

        /** Hostwaarde die nooit een tenant oplevert (dus 404). */
        private const val ONBRUIKBAAR = ""

        /**
         * Attribuut op de exchange met de [Oin], alleen voor de observation-convention van `core.tenancy` (de Reactor
         * Context is er niet bij het afsluiten van de observation). `internal`: andere modules lezen de tenant uitsluitend
         * via [TenantContext].
         */
        internal const val ATTRIBUUT = "nl.nlportal.core.tenancy.Oin"
    }
}