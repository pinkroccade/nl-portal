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

import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.payment.direct.PaymentTestTenants
import nl.nlportal.payment.direct.service.WebhookHandtekeningen.Companion.HEADER_X_GCS_KEYID
import nl.nlportal.payment.direct.service.WebhookHandtekeningen.Companion.HEADER_X_GCS_SIGNATURE
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.commons.codec.binary.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Seam: een postsale-webhook werkt de taak bij via de Objecten-koppeling van de tenant van het hostdomein (ADR 0021): een webhook op
 * host B raakt de Objecten API van tenant A niet, ook niet met een taak-UUID die bij A hoort.
 */
class DirectPaymentWebhookObjectenTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD
    private val geheimWijdeVeld = "geheim-van-wijde-veld"
    private val taakId = UUID.randomUUID().toString()

    private lateinit var objectenBosrand: MockWebServer
    private lateinit var objectenWijdeVeld: MockWebServer
    private lateinit var service: DirectPaymentWebhookService

    private val taakJson =
        """
        {"uuid":"$taakId","type":"t","record":{"index":1,"typeVersion":1,"startAt":"2022-05-25","data":{"titel":"Een betaal taak","status":"open","soort":"ogonebetaling",
         "eigenaar":"erfpacht","identificatie":{"type":"bsn","value":"569312863"},"koppeling":{"registratie":"zaak","uuid":"5551a7c5-4e92-43e6-8d23-80359b7e22b7"},
         "ogonebetaling":{"bedrag":147.43,"betaalkenmerk":"abcdef1234","pspid":"TAX"},"verwerker_taak_id":"$taakId"}}}
        """.trimIndent().replace("\n", "")

    @BeforeEach
    fun start() {
        objectenBosrand = MockWebServer().also { it.start() }
        objectenWijdeVeld = MockWebServer().also { it.start() }
        val tenants =
            listOf(bosrand to objectenBosrand, wijdeVeld to objectenWijdeVeld).map { (oin, server) ->
                val tenant = PaymentTestTenants.tenant(oin)
                tenant.copy(koppelingen = tenant.koppelingen + ("objectenapi" to TestTenants.koppeling(server.url("/").toString().trimEnd('/'), oin = oin)))
            }
        val geheimen = PaymentTestTenants.geheimen(bosrand to "geheim-van-bosrand", wijdeVeld to geheimWijdeVeld)
        val register = TestTenants.register(*tenants.toTypedArray(), geheimen = geheimen)
        service = DirectPaymentWebhookService(WebhookHandtekeningen(TestTenants.directory(*tenants.toTypedArray()), geheimen), ObjectsApiClient(register))
    }

    @AfterEach
    fun stop() {
        objectenBosrand.shutdown()
        objectenWijdeVeld.shutdown()
    }

    private val body get() =
        """{"apiVersion":"v1","id":"1","merchantId":"TAX","type":"payment.created","created":"2020-12-09T11:20:40.3744722+01:00",""" +
            """"payment":{"id":"1","status":"COMPLETED","statusOutput":{"statusCode":9,"statusCategory":"CREATED"},""" +
            """"paymentOutput":{"references":{"merchantReference":"$taakId"},"paymentMethod":"card","amountOfMoney":{"amount":1000,"currencyCode":"EUR"}}}}"""

    private fun koppenVan(
        oin: Oin,
        geheim: String,
    ): HttpHeaders {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(geheim.toByteArray(), "HmacSHA256")) }
        return HttpHeaders().apply {
            add(HEADER_X_GCS_KEYID, "webhook-sleutel-${oin.value}")
            add(HEADER_X_GCS_SIGNATURE, Base64.encodeBase64String(mac.doFinal(body.toByteArray())))
        }
    }

    @Test
    fun `een webhook op het domein van B werkt de taak bij via de Objecten API van B en raakt A niet`() {
        objectenWijdeVeld.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(taakJson))
        objectenWijdeVeld.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(taakJson))

        val antwoord = TestTenants.metTenant(wijdeVeld) { service.handlePostSale(koppenVan(wijdeVeld, geheimWijdeVeld), body) }

        assertThat(antwoord).isEqualTo("Request successful processed for order $taakId")
        assertThat(objectenWijdeVeld.takeRequest().path).isEqualTo("/api/v2/objects/$taakId")
        val bijwerken = objectenWijdeVeld.takeRequest()
        assertThat(bijwerken.method).isEqualTo("PUT")
        assertThat(bijwerken.body.readUtf8()).contains("afgerond")
        assertThat(objectenBosrand.requestCount).isZero()
    }

    @Test
    fun `een handtekening van A op het domein van B raakt geen Objecten API`() {
        val antwoord = TestTenants.metTenant(wijdeVeld) { service.handlePostSale(koppenVan(bosrand, "geheim-van-bosrand"), body) }

        assertThat(antwoord).isEqualTo("Request is not valid")
        assertThat(objectenBosrand.requestCount).isZero()
        assertThat(objectenWijdeVeld.requestCount).isZero()
    }
}