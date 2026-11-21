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
package nl.nlportal.payment.direct

import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.payment.direct.configuratie.DirectPaymentConfiguratie
import nl.nlportal.payment.direct.configuratie.DirectPaymentProfiel
import nl.nlportal.payment.direct.configuratie.DirectPaymentTenantConfiguratie

/** Testtenants met payment-direct: een koppeling die ondertekent met `<apiSleutelId>` en profiel `belastingzaken` (PSP-ID `TAX`). */
object PaymentTestTenants {
    const val PROFIEL = "belastingzaken"

    /** De webhooksleutel van de testtenant [TestTenants.BOSRAND] in de IT's: sleutel-ID en het geheim waarmee de test de webhook ondertekent. */
    val WEBHOOK_SLEUTEL = "webhook-sleutel-${TestTenants.BOSRAND.value}"
    const val WEBHOOK_GEHEIM = "418f68e5-fcff-413f-9b06-77649ba3c52e"

    fun geheimen(vararg tenants: Pair<Oin, String>): GeheimenBron =
        TestTenants.geheimen(
            *tenants
                .flatMap { (oin, geheim) ->
                    listOf(
                        TestTenants.geheim("api-geheim", oin) to "api-geheim-$geheim",
                        TestTenants.geheim("webhook-geheim", oin) to geheim,
                    )
                }.toTypedArray(),
        )

    fun profiel(
        oin: Oin,
        webhookSleutelId: String? = "webhook-sleutel-${oin.value}",
        aanpassen: (DirectPaymentProfiel) -> DirectPaymentProfiel = { it },
    ) = aanpassen(
        DirectPaymentProfiel(
            pspId = "TAX",
            returnUrl = "https://${oin.value}.example/terug",
            webhookSleutelId = webhookSleutelId,
            webhookGeheim = webhookSleutelId?.let { TestTenants.geheim("webhook-geheim", oin) },
        ),
    )

    fun tenant(
        oin: Oin,
        bronUrl: String? = null,
        configuratie: DirectPaymentConfiguratie? = DirectPaymentConfiguratie(profielen = mapOf(PROFIEL to profiel(oin))),
        apiSleutelId: String = "api-sleutel-${oin.value}",
    ): TenantDefinition =
        TestTenants.tenant(
            oin,
            koppelingen =
                bronUrl
                    ?.let {
                        mapOf(
                            DirectPaymentTenantConfiguratie.NAAM to
                                TestTenants.koppeling(
                                    it,
                                    TenantKoppelingAuth(TenantKoppelingAuthType.GCS_V1_HMAC, clientId = apiSleutelId, secret = TestTenants.geheim("api-geheim", oin)),
                                    oin = oin,
                                ),
                        )
                    }.orEmpty(),
            modules = configuratie?.let { mapOf(DirectPaymentTenantConfiguratie.NAAM to it) }.orEmpty(),
        )
}