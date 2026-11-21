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
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.payment.direct.PaymentTestTenants
import nl.nlportal.payment.direct.PaymentTestTenants.PROFIEL
import nl.nlportal.payment.direct.TestHelper
import nl.nlportal.payment.direct.client.DirectPaymentClient
import nl.nlportal.payment.direct.configuratie.DirectPaymentConfiguratie
import nl.nlportal.payment.direct.domain.DirectPaymentRequest
import nl.nlportal.payment.direct.domain.DirectPaymentStatusCategory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Seam 1 van epic #499 voor payment-direct: de service met een tenantcontext, tegen een nep-betaalprovider per tenant. De betaling
 * (en daarmee de API-sleutel van de tenant) gaat alleen naar de koppeling van de eigen tenant.
 */
class DirectPaymentTenantTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD

    private lateinit var bosrandBron: MockWebServer
    private lateinit var wijdeVeldBron: MockWebServer

    @BeforeEach
    fun start() {
        bosrandBron = MockWebServer().apply { start() }
        wijdeVeldBron = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        bosrandBron.shutdown()
        wijdeVeldBron.shutdown()
    }

    private fun url(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    private fun service(
        vararg tenants: nl.nlportal.core.tenancy.TenantDefinition,
    ): DirectPaymentService {
        val register = TestTenants.register(*tenants, geheimen = PaymentTestTenants.geheimen(bosrand to "a", wijdeVeld to "b"))
        return DirectPaymentService(TestTenants.directory(*tenants), DirectPaymentClient(register))
    }

    private val verzoek = DirectPaymentRequest(identifier = PROFIEL, amount = 100.25, reference = "12345", orderId = "123456")

    @Test
    fun `een betaling gaat alleen naar de koppeling van de eigen tenant, ondertekend met de sleutel van die tenant`() {
        bosrandBron.enqueue(TestHelper.mockResponseFromFile("/data/payment-response.json"))
        wijdeVeldBron.enqueue(TestHelper.mockResponseFromFile("/data/payment-response.json"))
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)), PaymentTestTenants.tenant(wijdeVeld, url(wijdeVeldBron)))

        val antwoord = TestTenants.metTenant(bosrand) { service.doDirectPayment(verzoek) }

        assertThat(antwoord.redirectUrl).startsWith("https://payment.preprod.direct.worldline-solutions.com/hostedcheckout/")
        assertThat(wijdeVeldBron.requestCount).isZero()
        val aanroep = bosrandBron.takeRequest()
        assertThat(aanroep.method).isEqualTo("POST")
        assertThat(aanroep.path).isEqualTo("/v2/TAX/hostedcheckouts")
        assertThat(aanroep.getHeader("Authorization")).startsWith("GCS v1HMAC:api-sleutel-${bosrand.value}:")

        TestTenants.metTenant(wijdeVeld) { service.doDirectPayment(verzoek) }

        assertThat(bosrandBron.requestCount).isEqualTo(1)
        assertThat(wijdeVeldBron.takeRequest().getHeader("Authorization")).startsWith("GCS v1HMAC:api-sleutel-${wijdeVeld.value}:")
    }

    @Test
    fun `het verzoek bevat bedrag in centen, valuta, taal, return-url, webhook-url en de opties van het profiel`() {
        bosrandBron.enqueue(TestHelper.mockResponseFromFile("/data/payment-response.json"))
        val configuratie =
            DirectPaymentConfiguratie(
                webhookUrl = "https://bosrand.example/api/public/payment/direct/postsale",
                customTemplateUrl = "sjabloon.html",
                toonResultaatPagina = true,
                profielen = mapOf(PROFIEL to PaymentTestTenants.profiel(bosrand) { it.copy(creditcardAan = true, aantalPogingen = 3, taal = "en_US") }),
            )
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron), configuratie))

        TestTenants.metTenant(bosrand) { service.doDirectPayment(verzoek.copy(returnUrl = "https://bosrand.example/klaar")) }

        val body = bosrandBron.takeRequest().body.readUtf8()
        assertThat(body)
            .contains("\"amount\":10025", "\"currencyCode\":\"EUR\"", "\"merchantReference\":\"123456\"", "\"descriptor\":\"12345\"")
            .contains("\"locale\":\"en_US\"", "\"returnUrl\":\"https://bosrand.example/klaar\"", "\"showResultPage\":true", "\"allowedNumberOfPaymentAttempts\":3")
            .contains("\"variant\":\"sjabloon.html\"", "\"webhooksUrls\":[\"https://bosrand.example/api/public/payment/direct/postsale\"]", "\"authorizationMode\":\"SALE\"")
    }

    @Test
    fun `zonder opties in de configuratie gebruikt het verzoek de standaardwaarden van het profiel`() {
        bosrandBron.enqueue(TestHelper.mockResponseFromFile("/data/payment-response.json"))
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)))

        TestTenants.metTenant(bosrand) { service.doDirectPayment(verzoek) }

        val body = bosrandBron.takeRequest().body.readUtf8()
        assertThat(body).contains("\"locale\":\"nl_NL\"", "\"returnUrl\":\"https://${bosrand.value}.example/terug\"", "\"allowedNumberOfPaymentAttempts\":1")
        assertThat(body).contains("\"variant\":null", "\"feedbacks\":null", "\"cardPaymentMethodSpecificInput\":null")
    }

    @Test
    fun `een profiel is ook te vinden op PSP-ID`() {
        bosrandBron.enqueue(TestHelper.mockResponseFromFile("/data/payment-response.json"))
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)))

        TestTenants.metTenant(bosrand) { service.doDirectPayment(verzoek.copy(identifier = "TAX")) }

        assertThat(bosrandBron.requestCount).isEqualTo(1)
    }

    @Test
    fun `de status wordt opgehaald bij de koppeling van de eigen tenant`() {
        bosrandBron.enqueue(TestHelper.mockResponseFromFile("/data/payment-status-response.json"))
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)), PaymentTestTenants.tenant(wijdeVeld, url(wijdeVeldBron)))

        val status = TestTenants.metTenant(bosrand) { service.getDirectPaymentStatus(PROFIEL, "abc123") }

        assertThat(status.status).isEqualTo(DirectPaymentStatusCategory.SUCCESSFUL)
        assertThat(wijdeVeldBron.requestCount).isZero()
        val aanroep = bosrandBron.takeRequest()
        assertThat(aanroep.method).isEqualTo("GET")
        assertThat(aanroep.path).isEqualTo("/v2/TAX/hostedcheckouts/abc123")
        assertThat(aanroep.getHeader("Authorization")).startsWith("GCS v1HMAC:api-sleutel-${bosrand.value}:")
    }

    @Test
    fun `een hosted checkout-id met een padscheider bereikt de bron niet`() {
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)))

        assertThrows<KoppelingException> { TestTenants.metTenant(bosrand) { service.getDirectPaymentStatus(PROFIEL, "../../andere") } }

        assertThat(bosrandBron.requestCount).isZero()
    }

    @Test
    fun `een onbekend profiel is een 400 en bereikt de bron niet`() {
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)))

        val fout = assertThrows<ResponseStatusException> { TestTenants.metTenant(bosrand) { service.doDirectPayment(verzoek.copy(identifier = "bestaat-niet")) } }
        val statusFout = assertThrows<ResponseStatusException> { TestTenants.metTenant(bosrand) { service.getDirectPaymentStatus("bestaat-niet", "1") } }

        assertThat(fout.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(statusFout.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(bosrandBron.requestCount).isZero()
    }

    @Test
    fun `een profiel van een andere tenant bestaat niet voor deze tenant`() {
        val alleenWijdeVeld = DirectPaymentConfiguratie(profielen = mapOf("parkeren" to PaymentTestTenants.profiel(wijdeVeld)))
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)), PaymentTestTenants.tenant(wijdeVeld, url(wijdeVeldBron), alleenWijdeVeld))

        val fout = assertThrows<ResponseStatusException> { TestTenants.metTenant(bosrand) { service.doDirectPayment(verzoek.copy(identifier = "parkeren")) } }

        assertThat(fout.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(wijdeVeldBron.requestCount).isZero()
    }

    @Test
    fun `een tenant zonder moduleconfiguratie krijgt een fout en niet de gegevens van een andere tenant`() {
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)), PaymentTestTenants.tenant(wijdeVeld, configuratie = null))

        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(wijdeVeld) { service.doDirectPayment(verzoek) } }
        assertThat(bosrandBron.requestCount).isZero()
    }

    @Test
    fun `zonder tenant in de context volgt een fout in plaats van een standaardtenant`() {
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)))

        assertThrows<TenantContextMissingException> { runBlocking { service.doDirectPayment(verzoek) } }
        assertThrows<TenantContextMissingException> { runBlocking { service.getDirectPaymentStatus(PROFIEL, "1") } }
        assertThat(bosrandBron.requestCount).isZero()
    }

    @Test
    fun `een fout van de bron wordt een koppelingsfout zonder de details van de bron`() {
        bosrandBron.enqueue(MockResponse().setResponseCode(500).setBody("api-geheim BSN 123456782"))
        val service = service(PaymentTestTenants.tenant(bosrand, url(bosrandBron)))

        val fout = assertThrows<KoppelingException> { TestTenants.metTenant(bosrand) { service.doDirectPayment(verzoek) } }

        assertThat(fout.message).doesNotContain("123456782", "api-geheim")
    }
}