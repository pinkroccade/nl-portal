/*
 * Copyright 2015-2025 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.payment.direct.client

import com.onlinepayments.domain.CreateHostedCheckoutRequest
import com.onlinepayments.domain.CreateHostedCheckoutResponse
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.get
import nl.nlportal.core.koppeling.post
import nl.nlportal.payment.direct.configuratie.DirectPaymentProfiel
import nl.nlportal.payment.direct.configuratie.DirectPaymentTenantConfiguratie
import nl.nlportal.payment.direct.domain.DirectPaymentStatusResponse
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/**
 * De aanroepen naar Worldline Direct, via de koppeling van het profiel van de huidige tenant. De koppeling ondertekent elk verzoek
 * (`gcs-v1-hmac`) met de API-sleutel van het profiel; deze klasse bewaart geen client en kent geen sleutel.
 */
class DirectPaymentClient(
    private val register: KoppelingRegister,
) {
    suspend fun hostedCheckout(
        profiel: DirectPaymentProfiel,
        checkoutRequest: CreateHostedCheckoutRequest,
    ): CreateHostedCheckoutResponse = koppeling(profiel).post("/v2/${segment(profiel.pspId)}/hostedcheckouts", checkoutRequest, ACCEPT_JSON)

    suspend fun hostedCheckoutStatus(
        profiel: DirectPaymentProfiel,
        hostedCheckoutId: String,
    ): DirectPaymentStatusResponse = koppeling(profiel).get("/v2/${segment(profiel.pspId)}/hostedcheckouts/${segment(hostedCheckoutId)}", headers = ACCEPT_JSON)

    private suspend fun koppeling(profiel: DirectPaymentProfiel) = register.current(DirectPaymentTenantConfiguratie.NAAM, profiel.koppelingNaam())

    private fun segment(waarde: String) = UriUtils.encodePathSegment(waarde, StandardCharsets.UTF_8)

    private companion object {
        val ACCEPT_JSON = mapOf("Accept" to "application/json")
    }
}