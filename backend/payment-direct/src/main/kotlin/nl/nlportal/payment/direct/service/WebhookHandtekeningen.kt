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
package nl.nlportal.payment.direct.service

import com.onlinepayments.communication.RequestHeader
import com.onlinepayments.json.DefaultMarshaller
import com.onlinepayments.webhooks.SecretKeyNotAvailableException
import com.onlinepayments.webhooks.SecretKeyStore
import com.onlinepayments.webhooks.SignatureValidationException
import com.onlinepayments.webhooks.WebhooksHelper
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.payment.direct.configuratie.DirectPaymentConfiguratie
import org.springframework.http.HttpHeaders

/**
 * De webhooksleutels per tenant, bij opstarten opgelost uit de moduleconfiguratie. Een handtekening wordt gecontroleerd tegen de
 * sleutels van precies een tenant: de tenant van het hostdomein waarop de webhook binnenkwam. Een `keyId` van een andere tenant
 * is daar onbekend en geeft dus een ongeldige handtekening; er bestaat geen JVM-brede sleutelopslag.
 */
class WebhookHandtekeningen(
    directory: TenantDirectory,
    bron: GeheimenBron,
) {
    private val perTenant: Map<Oin, WebhooksHelper> =
        directory.tenants
            .mapNotNull { tenant -> tenant.module<DirectPaymentConfiguratie>()?.let { tenant.oin to sleutels(it, bron) } }
            .also(::weigerSleutelsBijMeerdereTenants)
            .associate { (oin, sleutels) -> oin to WebhooksHelper(DefaultMarshaller.INSTANCE, sleutelopslag(sleutels)) }

    /** Is de handtekening van dit verzoek geldig met een sleutel van [tenant]? Een tenant zonder webhooksleutels weigert alles. */
    fun isGeldig(
        tenant: Oin,
        headers: HttpHeaders,
        body: String,
    ): Boolean {
        val helper = perTenant[tenant] ?: return false
        val requestHeaders =
            listOf(
                RequestHeader(HEADER_X_GCS_SIGNATURE, headers[HEADER_X_GCS_SIGNATURE]?.firstOrNull()),
                RequestHeader(HEADER_X_GCS_KEYID, headers[HEADER_X_GCS_KEYID]?.firstOrNull()),
            )
        return try {
            helper.unmarshal(body, requestHeaders)
            true
        } catch (e: SignatureValidationException) {
            false
        }
    }

    /** Een onbekend sleutel-ID geeft `SecretKeyNotAvailableException`: de SDK geeft anders een NPE op `null`. */
    private fun sleutelopslag(sleutels: Map<String, String>) = SecretKeyStore { sleutel -> sleutels[sleutel] ?: throw SecretKeyNotAvailableException(sleutel, "Sleutel is onbekend voor deze tenant") }

    /**
     * Een sleutel-ID bij twee tenants wijst op een gekopieerd sleutelpaar (ADR 0021 besluit 5): weigeren bij het opstarten. De melding
     * noemt de OIN's, nooit het sleutel-ID of een geheim.
     */
    private fun weigerSleutelsBijMeerdereTenants(sleutelsPerTenant: List<Pair<Oin, Map<String, String>>>) {
        val dubbel =
            sleutelsPerTenant
                .flatMap { (oin, sleutels) -> sleutels.keys.map { it to oin } }
                .groupBy({ it.first }, { it.second })
                .values
                .filter { it.size > 1 }
        check(dubbel.isEmpty()) {
            "Een webhook-sleutel-ID van payment-direct staat bij meer dan een tenant (OIN's: ${dubbel.flatten().distinct().joinToString()}); " +
                "elke tenant heeft een eigen sleutel-ID"
        }
    }

    private fun sleutels(
        configuratie: DirectPaymentConfiguratie,
        bron: GeheimenBron,
    ): Map<String, String> =
        configuratie.profielen.values
            .mapNotNull { profiel -> profiel.webhookSleutelId?.let { id -> profiel.webhookGeheim?.let { id to bron.vereis(it) } } }
            .toMap()

    companion object {
        const val HEADER_X_GCS_SIGNATURE: String = "X-GCS-Signature"
        const val HEADER_X_GCS_KEYID: String = "X-GCS-KeyId"
    }
}