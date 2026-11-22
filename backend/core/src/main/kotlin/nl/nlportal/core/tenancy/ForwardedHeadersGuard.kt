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

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.cloud.CloudPlatform
import org.springframework.core.env.Environment

/**
 * Weigert het opstarten als Spring zelf `Forwarded`/`X-Forwarded-*` verwerkt. Die verwerking volgt de headers van elk
 * adres en herschrijft `request.uri` en `remoteAddress`, waarmee de regel "alleen van de vertrouwde proxies" van
 * [TenantWebFilter] zou vervallen. Op Kubernetes zet Spring Boot haar zelf aan als `server.forward-headers-strategy`
 * ontbreekt, dus daar moet de waarde `none` expliciet staan.
 */
class ForwardedHeadersGuard(
    private val environment: Environment,
    private val transformerAanwezig: () -> Boolean,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val fouten = controleer()
        check(fouten.isEmpty()) { "Ongeldige forwarded-configuratie:\n" + fouten.joinToString("\n") { " - $it" } }
    }

    fun controleer(): List<String> =
        buildList {
            val strategie = environment.getProperty(STRATEGIE)?.trim()?.lowercase()
            when {
                strategie == "native" || strategie == "framework" -> {
                    add("$STRATEGIE=$strategie laat Spring forwarded-headers van elk adres volgen; zet hem op none, de tenantresolutie volgt alleen vertrouwde proxies")
                }

                strategie == null && CloudPlatform.getActive(environment)?.isUsingForwardHeaders == true -> {
                    add("$STRATEGIE ontbreekt en Spring Boot zet de forwarded-verwerking aan op dit cloudplatform; zet $STRATEGIE=none")
                }
            }
            if (transformerAanwezig()) {
                add("Een ForwardedHeaderTransformer in de context herschrijft requests op forwarded-headers van elk adres; verwijder hem")
            }
        }

    private companion object {
        const val STRATEGIE = "server.forward-headers-strategy"
    }
}