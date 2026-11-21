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

import java.util.Locale

/**
 * Onveranderlijke allowlist van domein naar tenant, bij opstarten gebouwd uit de gevalideerde tenantbestanden.
 * Er is geen standaardtenant: een onbekend domein levert `null`.
 */
class TenantDirectory(
    val tenants: List<TenantDefinition>,
) {
    private val perDomein: Map<String, TenantDefinition> =
        buildMap {
            tenants.forEach { tenant ->
                tenant.domeinen.forEach { domein ->
                    require(put(domein, tenant) == null) { "Domein $domein hoort bij meerdere tenants" }
                }
            }
        }

    /** [host] moet al genormaliseerd zijn (zie [normaliseerHost]). */
    fun forHost(host: String): TenantDefinition? = perDomein[host]

    fun forOin(oin: Oin): TenantDefinition? = tenants.firstOrNull { it.oin == oin }

    /**
     * De configuratie van de module met klasse [klasse] voor de tenant uit de [TenantContext] (fail-closed: zonder tenant een
     * [TenantContextMissingException], voor een tenant zonder configuratie van deze module een
     * [nl.nlportal.core.util.ModuleNotConfiguredException]).
     */
    suspend fun <T : Any> module(klasse: kotlin.reflect.KClass<T>): T {
        val oin = TenantContext.current()
        return (forOin(oin) ?: throw TenantContextMissingException()).requireModule(klasse)
    }

    suspend inline fun <reified T : Any> module(): T = module(T::class)

    companion object {
        /**
         * Normaliseert de waarde van een `Host`-header of forwarded host: kleine letters, zonder poort en zonder één
         * trailing dot. Geeft `null` voor wat geen hostnaam met optionele poort is (IP-literal, rommel, lege waarde).
         */
        fun normaliseerHost(ruw: String): String? {
            if (ruw.startsWith("[")) return null
            val dubbelepunt = ruw.lastIndexOf(':')
            val naam =
                if (dubbelepunt < 0) {
                    ruw
                } else {
                    val poort = ruw.substring(dubbelepunt + 1)
                    if (poort.isEmpty() || poort.length > 5 || !poort.all { it in '0'..'9' } || poort.toInt() > 65535) return null
                    ruw.substring(0, dubbelepunt)
                }
            return naam.removeSuffix(".").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && ':' !in it }
        }
    }
}