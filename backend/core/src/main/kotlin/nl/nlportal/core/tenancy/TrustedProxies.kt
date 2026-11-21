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

import org.springframework.security.web.util.matcher.IpAddressMatcher
import java.net.InetSocketAddress

/**
 * De adressen (IP of CIDR) van de proxy's waarvan `Forwarded` en `X-Forwarded-Host` gelden. De ingress overschrijft
 * die headers altijd (#191); van elk ander adres telt alleen de `Host`-header. Een ongeldig adres weigert het opstarten.
 */
class TrustedProxies(
    adressen: List<String>,
) {
    private val matchers =
        adressen.map { adres ->
            require(adres.substringAfter('/', "").trim().toIntOrNull() != 0) { "Het blok $adres vertrouwt elk adres; noem de adressen van de ingress" }
            IpAddressMatcher(adres)
        }

    fun trusts(afzender: InetSocketAddress?): Boolean {
        val ip = afzender?.address?.hostAddress?.substringBefore('%') ?: return false
        return matchers.any { it.matches(ip) }
    }
}