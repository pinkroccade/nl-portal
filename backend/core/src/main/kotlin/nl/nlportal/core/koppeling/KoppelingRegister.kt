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

import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.util.requireConfigured
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI

/**
 * Het onveranderlijke register `OIN -> koppelingen`, bij opstarten gebouwd. [current] leest de tenant uit de
 * [TenantContext] (fail-closed: zonder tenant volgt een fout, nooit een standaardtenant) en geeft de [KoppelingClient]
 * van die tenant. Elke tenant en koppeling heeft eigen authenticatie, een eigen limiet en een eigen circuit breaker.
 *
 * Een koppeling wordt pas bij het eerste gebruik opgebouwd: ontbrekende configuratie geeft dan een duidelijke fout
 * en laat het opstarten slagen, zoals voor de oude [KoppelingenProperties.voorModule].
 */
class KoppelingRegister internal constructor(
    private val perTenant: Map<Oin, Map<String, Lazy<KoppelingClient>>>,
) {
    /** De koppeling van de huidige tenant voor [module]: de gekozen koppeling, anders die met de modulenaam. */
    suspend fun current(
        module: String,
        koppeling: String? = null,
    ): KoppelingClient = voor(TenantContext.current(), module, koppeling)

    /** Voor reactieve code. */
    fun currentMono(
        module: String,
        koppeling: String? = null,
    ): Mono<KoppelingClient> = TenantContext.currentMono().map { voor(it, module, koppeling) }

    internal fun voor(
        oin: Oin,
        module: String,
        koppeling: String?,
    ): KoppelingClient {
        val naam = koppeling ?: module
        val koppelingen = perTenant[oin].requireConfigured(module, "koppelingen van de tenant")
        return koppelingen[naam].requireConfigured(module, "koppeling '$naam'").value
    }

    companion object {
        /** Bouwt het register voor [koppelingenPerTenant]. */
        fun bouw(
            koppelingenPerTenant: Map<Oin, Map<String, Koppeling>>,
            webClientBuilder: WebClient.Builder,
            resilience: KoppelingResilienceInstellingen = KoppelingResilienceInstellingen(),
            zgwJwtGenerator: ZgwJwtGenerator = ZgwJwtGenerator(),
        ): KoppelingRegister {
            val factory = KoppelingWebClientFactory(webClientBuilder, zgwJwtGenerator)
            return KoppelingRegister(
                koppelingenPerTenant.mapValues { (oin, koppelingen) ->
                    koppelingen.mapValues { (naam, koppeling) ->
                        lazy { maakClient(factory, oin, naam, koppeling, resilience) }
                    }
                },
            )
        }

        private fun maakClient(
            factory: KoppelingWebClientFactory,
            oin: Oin,
            naam: String,
            koppeling: Koppeling,
            resilience: KoppelingResilienceInstellingen,
        ): KoppelingClient {
            val basis = URI(koppeling.url.requireConfigured(naam, "url"))
            return KoppelingClient(naam, basis, factory.webClient(naam, koppeling), KoppelingBewaker(naam, "${oin.value}/$naam", resilience))
        }
    }
}