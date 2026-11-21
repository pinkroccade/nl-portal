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
package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Clock

/**
 * Start van de login (`GET /oauth2/authorization/{oin}?inlogmiddel=...&returnTo=...`). Per tenant geldt:
 *
 *  - het inlogmiddel moet bij de tenant aanstaan (met één ingeschakeld middel mag de parameter ontbreken); een ander
 *    middel is een 400 met securityevent. Dit is alleen gemak: de callback toetst het inlogmiddel opnieuw uit het token;
 *  - `kc_idp_hint` volgt het gekozen middel (het testinlogmiddel heeft geen hint: lokale gebruikersnaam en wachtwoord);
 *  - elke login begint met `prompt=login` en bewaart het beginmoment voor de `auth_time`-controle, zodat Keycloak niet stil
 *    een bestaande sessie teruggeeft;
 *  - het return-pad is alleen een relatief pad binnen het portaal; een ander pad valt weg (geen open redirect).
 *
 * PKCE (S256), `state` en `nonce` komen van Spring Security.
 */
class InlogStartResolver(
    private val registraties: ReactiveClientRegistrationRepository,
    private val tenants: TenantDirectory,
    private val clock: Clock,
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : ServerOAuth2AuthorizationRequestResolver {
    override fun resolve(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> {
        val pad = exchange.request.path.value()
        if (!pad.startsWith(BffNamen.INLOGPAD)) return Mono.empty()
        val id = pad.removePrefix(BffNamen.INLOGPAD)
        return if (id.isEmpty() || '/' in id) Mono.empty() else resolve(exchange, id)
    }

    override fun resolve(
        exchange: ServerWebExchange,
        clientRegistrationId: String,
    ): Mono<OAuth2AuthorizationRequest> =
        TenantContext.currentMono().flatMap { oin ->
            val tenant = tenants.forOin(oin) ?: return@flatMap Mono.empty()
            val middel = kies(tenant, exchange.request.queryParams.getFirst("inlogmiddel"))
            if (middel == null) {
                gebeurtenissen.registreer(Beveiligingsgebeurtenis(GebeurtenisType.LOGIN_START_GEWEIGERD, oin.value, null, "geweigerd", Reden.INLOGMIDDEL_PARAMETER))
                return@flatMap Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST))
            }
            val returnTo =
                exchange.request.queryParams
                    .getFirst("returnTo")
                    ?.takeIf { BffNamen.VEILIG_PAD.matches(it) }
            val loginStart = clock.instant().toEpochMilli().toString()
            // Per verzoek een eigen resolver: de customizer kent het verzoek niet, en het object is goedkoop.
            DefaultServerOAuth2AuthorizationRequestResolver(registraties)
                .apply {
                    setAuthorizationRequestCustomizer { builder ->
                        builder
                            .additionalParameters { parameters ->
                                parameters["prompt"] = "login"
                                if (middel != Inlogmiddel.TEST) parameters["kc_idp_hint"] = middel.waarde
                            }.attributes { attributen ->
                                attributen[BffNamen.LOGIN_START] = loginStart
                                returnTo?.let { attributen[BffNamen.RETURN_TO] = it }
                            }
                    }
                }.resolve(exchange, clientRegistrationId)
        }

    private fun kies(
        tenant: TenantDefinition,
        gevraagd: String?,
    ): Inlogmiddel? {
        if (gevraagd == null) return tenant.inlogmiddelen.singleOrNull()
        return Inlogmiddel.entries.firstOrNull { it.waarde == gevraagd }?.takeIf { it in tenant.inlogmiddelen }
    }
}