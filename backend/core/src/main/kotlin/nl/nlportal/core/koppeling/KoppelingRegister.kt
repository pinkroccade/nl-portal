/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.core.koppeling

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.core.util.requireConfigured
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI

/**
 * Het onveranderlijke register `OIN -> koppelingen`, bij opstarten volledig gebouwd: alle clients, limieten en circuit
 * breakers bestaan zodra het register bestaat. [current] leest de tenant uit de [TenantContext] (fail-closed: zonder tenant
 * volgt een fout, nooit een standaardtenant) en geeft de [KoppelingClient] van die tenant. Elke tenant en koppeling heeft
 * eigen authenticatie, een eigen limiet en een eigen circuit breaker.
 *
 * Een koppeling zonder (volledige) configuratie staat niet in het register en telt als "niet geconfigureerd": [current]
 * geeft dan een duidelijke fout. Een ongeldige basis-URL laat het bouwen van het register falen.
 */
class KoppelingRegister internal constructor(
    private val perTenant: Map<Oin, Map<String, KoppelingClient>>,
) {
    /** De koppeling van de huidige tenant voor [module]: de gekozen koppeling, anders die met de modulenaam. */
    suspend fun current(
        module: String,
        koppeling: String? = null,
    ): KoppelingClient = voor(TenantContext.current(), module, koppeling)

    internal fun voor(
        oin: Oin,
        module: String,
        koppeling: String?,
    ): KoppelingClient {
        val naam = koppeling ?: module
        val koppelingen = perTenant[oin].requireConfigured(module, "koppelingen van de tenant")
        return koppelingen[naam].requireConfigured(module, "koppeling '$naam'")
    }

    internal companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Bouwt het register. [koppelingenPerTenant] is de tussenfase (nog niet gemigreerde modules lezen `nl-portal.koppelingen`):
         * een onvolledige koppeling blijft dan weg met een waarschuwing. [gemigreerd] komt uit het tenantbestand: een koppeling
         * die niet te bouwen is weigert het opstarten, met de OIN in de melding (story 14). Bij dezelfde naam wint [gemigreerd].
         */
        fun bouw(
            koppelingenPerTenant: Map<Oin, Map<String, Koppeling>>,
            webClientBuilder: WebClient.Builder,
            resilience: KoppelingResilienceInstellingen = KoppelingResilienceInstellingen(),
            zgwJwtGenerator: ZgwJwtGenerator = ZgwJwtGenerator(),
            gemigreerd: Map<Oin, Map<String, Koppeling>> = emptyMap(),
        ): KoppelingRegister {
            val factory = KoppelingWebClientFactory(webClientBuilder, zgwJwtGenerator)
            val tenants = koppelingenPerTenant.keys + gemigreerd.keys
            return KoppelingRegister(
                tenants.associateWith { oin ->
                    val tussenfase = koppelingenPerTenant[oin].orEmpty().mapNotNull { (naam, koppeling) -> maakClient(factory, oin, naam, koppeling, resilience)?.let { naam to it } }
                    val eigen = gemigreerd[oin].orEmpty().map { (naam, koppeling) -> naam to maakStrengeClient(factory, oin, naam, koppeling, resilience) }
                    (tussenfase + eigen).toMap()
                },
            )
        }

        private fun maakStrengeClient(
            factory: KoppelingWebClientFactory,
            oin: Oin,
            naam: String,
            koppeling: Koppeling,
            resilience: KoppelingResilienceInstellingen,
        ): KoppelingClient =
            try {
                bouwClient(factory, oin, naam, koppeling, resilience)
            } catch (e: ModuleNotConfiguredException) {
                throw IllegalStateException("Tenant ${oin.value}: koppeling '$naam' is onvolledig: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Tenant ${oin.value}: koppeling '$naam' is ongeldig: ${e.message}", e)
            }

        private fun bouwClient(
            factory: KoppelingWebClientFactory,
            oin: Oin,
            naam: String,
            koppeling: Koppeling,
            resilience: KoppelingResilienceInstellingen,
        ): KoppelingClient {
            val basis = basisUrl(naam, koppeling.url.requireConfigured(naam, "url"))
            val eigenHeader = koppeling.auth.headerName.takeIf { koppeling.auth.type == KoppelingAuthType.API_KEY }
            return KoppelingClient(naam, oin, basis, factory.webClient(naam, koppeling), KoppelingBewaker(naam, "${oin.value}/$naam", resilience), eigenHeader)
        }

        private fun maakClient(
            factory: KoppelingWebClientFactory,
            oin: Oin,
            naam: String,
            koppeling: Koppeling,
            resilience: KoppelingResilienceInstellingen,
        ): KoppelingClient? =
            try {
                bouwClient(factory, oin, naam, koppeling, resilience)
            } catch (e: ModuleNotConfiguredException) {
                logger.warn { "Koppeling '$naam' van een tenant is niet geconfigureerd en staat niet in het register: ${e.message}" }
                null
            }

        private fun basisUrl(
            naam: String,
            url: String,
        ): URI {
            val basis = runCatching { URI(url) }.getOrNull()
            // De reden noemt de URL niet: hij kan een geheim bevatten.
            require(basis != null && basis.host != null && basis.scheme?.lowercase() in setOf("http", "https")) { "Koppeling '$naam': de url is ongeldig" }
            return basis
        }
    }
}