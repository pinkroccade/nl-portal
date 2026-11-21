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

import kotlinx.coroutines.runBlocking
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.payment.direct.PaymentTestTenants
import nl.nlportal.payment.direct.configuratie.DirectPaymentConfiguratie
import nl.nlportal.payment.direct.service.WebhookHandtekeningen.Companion.HEADER_X_GCS_KEYID
import nl.nlportal.payment.direct.service.WebhookHandtekeningen.Companion.HEADER_X_GCS_SIGNATURE
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import org.apache.commons.codec.binary.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.http.HttpHeaders
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * De postsale-webhook is publiek en anoniem; de handtekening is de enige controle. De tenant volgt uit het hostdomein (de
 * tenantcontext) en moet gelijk zijn aan de tenant van de `keyId` (epic #499, T21).
 */
class DirectPaymentWebhookTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD
    private val geheimBosrand = "geheim-van-bosrand"
    private val geheimWijdeVeld = "geheim-van-wijde-veld"
    private val sleutelBosrand = "webhook-sleutel-${bosrand.value}"
    private val sleutelWijdeVeld = "webhook-sleutel-${wijdeVeld.value}"

    private val objectsApi = Mockito.mock(ObjectsApiClient::class.java)

    private fun service(vararg tenants: nl.nlportal.core.tenancy.TenantDefinition): DirectPaymentWebhookService {
        val bron = PaymentTestTenants.geheimen(bosrand to geheimBosrand, wijdeVeld to geheimWijdeVeld)
        return DirectPaymentWebhookService(WebhookHandtekeningen(TestTenants.directory(*tenants), bron), objectsApi)
    }

    private val beide get() = arrayOf(PaymentTestTenants.tenant(bosrand), PaymentTestTenants.tenant(wijdeVeld))

    /** Een betaling waarvan de order geen UUID is: na de handtekeningcontrole is er niets meer te doen, dus geen Objecten API nodig. */
    private val body =
        """
        {"apiVersion":"v1","id":"34b8a607","merchantId":"TAX","type":"payment.created","created":"2020-12-09T11:20:40.3744722+01:00",
         "payment":{"id":"1","status":"COMPLETED","statusOutput":{"statusCode":9,"statusCategory":"CREATED"},
         "paymentOutput":{"references":{"merchantReference":"58fad5ab"},"paymentMethod":"card","amountOfMoney":{"amount":1000,"currencyCode":"EUR"}}}}
        """.trimIndent()

    private fun handtekening(
        geheim: String,
        inhoud: String = body,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(geheim.toByteArray(), "HmacSHA256")) }
        return Base64.encodeBase64String(mac.doFinal(inhoud.toByteArray()))
    }

    private fun koppen(
        sleutel: String?,
        handtekening: String?,
    ) = HttpHeaders().apply {
        sleutel?.let { add(HEADER_X_GCS_KEYID, it) }
        handtekening?.let { add(HEADER_X_GCS_SIGNATURE, it) }
    }

    private fun verwerk(
        service: DirectPaymentWebhookService,
        tenant: nl.nlportal.core.tenancy.Oin,
        headers: HttpHeaders,
    ) = TestTenants.metTenant(tenant) { service.handlePostSale(headers, body) }

    @Test
    fun `een geldige handtekening met een sleutel van de tenant van het domein wordt verwerkt`() {
        val service = service(*beide)

        assertThat(verwerk(service, bosrand, koppen(sleutelBosrand, handtekening(geheimBosrand)))).isEqualTo("Request successful processed for order 58fad5ab")
        assertThat(verwerk(service, wijdeVeld, koppen(sleutelWijdeVeld, handtekening(geheimWijdeVeld)))).isEqualTo("Request successful processed for order 58fad5ab")
    }

    @Test
    fun `een sleutel en handtekening van een andere tenant zijn op dit domein ongeldig, ook al zijn ze op hun eigen domein geldig`() {
        val service = service(*beide)
        val vanWijdeVeld = koppen(sleutelWijdeVeld, handtekening(geheimWijdeVeld))
        val vanBosrand = koppen(sleutelBosrand, handtekening(geheimBosrand))

        assertThat(verwerk(service, bosrand, vanWijdeVeld)).isEqualTo("Request is not valid")
        assertThat(verwerk(service, wijdeVeld, vanBosrand)).isEqualTo("Request is not valid")
        verifyNoInteractions(objectsApi)
    }

    @Test
    fun `dezelfde keyId bij twee tenants valideert elk tegen het eigen geheim`() {
        val gelijkeSleutel = "gedeelde-sleutel"
        val tenants =
            arrayOf(
                PaymentTestTenants.tenant(bosrand, configuratie = DirectPaymentConfiguratie(profielen = mapOf("p" to PaymentTestTenants.profiel(bosrand, gelijkeSleutel)))),
                PaymentTestTenants.tenant(wijdeVeld, configuratie = DirectPaymentConfiguratie(profielen = mapOf("p" to PaymentTestTenants.profiel(wijdeVeld, gelijkeSleutel)))),
            )
        val service = service(*tenants)

        assertThat(verwerk(service, bosrand, koppen(gelijkeSleutel, handtekening(geheimBosrand)))).startsWith("Request successful")
        assertThat(verwerk(service, bosrand, koppen(gelijkeSleutel, handtekening(geheimWijdeVeld)))).isEqualTo("Request is not valid")
        assertThat(verwerk(service, wijdeVeld, koppen(gelijkeSleutel, handtekening(geheimWijdeVeld)))).startsWith("Request successful")
    }

    @Test
    fun `een onjuiste handtekening, een onbekende sleutel en ontbrekende koppen zijn ongeldig`() {
        val service = service(*beide)

        assertThat(verwerk(service, bosrand, koppen(sleutelBosrand, handtekening("verkeerd-geheim")))).isEqualTo("Request is not valid")
        assertThat(verwerk(service, bosrand, koppen("onbekend", handtekening(geheimBosrand)))).isEqualTo("Request is not valid")
        assertThat(verwerk(service, bosrand, koppen(null, handtekening(geheimBosrand)))).isEqualTo("Request is not valid")
        assertThat(verwerk(service, bosrand, koppen(sleutelBosrand, null))).isEqualTo("Request is not valid")
        assertThat(verwerk(service, bosrand, HttpHeaders())).isEqualTo("Request is not valid")
        verifyNoInteractions(objectsApi)
    }

    @Test
    fun `een tenant zonder moduleconfiguratie of zonder webhooksleutels weigert elke webhook`() {
        val zonderSleutel = DirectPaymentConfiguratie(profielen = mapOf("p" to PaymentTestTenants.profiel(wijdeVeld, webhookSleutelId = null)))
        val service = service(PaymentTestTenants.tenant(bosrand, configuratie = null), PaymentTestTenants.tenant(wijdeVeld, configuratie = zonderSleutel))

        assertThat(verwerk(service, bosrand, koppen(sleutelBosrand, handtekening(geheimBosrand)))).isEqualTo("Request is not valid")
        assertThat(verwerk(service, wijdeVeld, koppen(sleutelWijdeVeld, handtekening(geheimWijdeVeld)))).isEqualTo("Request is not valid")
    }

    @Test
    fun `zonder tenant in de context volgt een fout in plaats van een standaardtenant`() {
        val service = service(*beide)

        assertThrows<TenantContextMissingException> { runBlocking { service.handlePostSale(koppen(sleutelBosrand, handtekening(geheimBosrand)), body) } }
    }

    @Test
    fun `een handtekening geldt alleen voor de body waarover hij is gezet`() {
        val service = service(*beide)

        val aangepast = body.replace("\"amount\":1000", "\"amount\":1")

        assertThat(TestTenants.metTenant(bosrand) { service.handlePostSale(koppen(sleutelBosrand, handtekening(geheimBosrand)), aangepast) }).isEqualTo("Request is not valid")
    }

    @Test
    fun `een betaling met een andere status dan gelukt of in behandeling wordt niet verwerkt`() {
        val service = service(*beide)
        val mislukt = body.replace("\"statusCode\":9", "\"statusCode\":50").replace("58fad5ab", "58fad5ab-dc2f-11ec-9075-f22a405ce707")

        val antwoord = TestTenants.metTenant(bosrand) { service.handlePostSale(koppen(sleutelBosrand, handtekening(geheimBosrand, mislukt)), mislukt) }

        assertThat(antwoord).isEqualTo("Request has not the correct status: 50")
        verifyNoInteractions(objectsApi)
    }
}