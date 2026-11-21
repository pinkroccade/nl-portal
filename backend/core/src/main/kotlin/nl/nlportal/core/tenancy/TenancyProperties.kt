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

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Runtime-brede configuratie van de tenancy (gewone Spring-config); de inhoud per tenant staat in de tenantbestanden.
 * Alleen de autoconfiguratie kent deze klasse. De tenancy staat aan zodra [directory] is ingesteld; de applicatie
 * (`:app`) vereist die waarde, zodat een productierun nooit zonder tenantbestanden start.
 */
@ConfigurationProperties(prefix = "nl-portal.tenancy")
class TenancyProperties {
    /** Map met `base.yaml` (optioneel) en per tenant `<oin>.yaml`. */
    var directory: String? = null

    /** IP-adressen of CIDR-blokken van de proxy's waarvan `Forwarded` en `X-Forwarded-Host` gelden (de ingress). */
    var trustedProxies: List<String> = emptyList()
}