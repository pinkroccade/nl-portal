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
package nl.nlportal.payment.direct

import nl.nlportal.commonground.authentication.WithBurgerUser
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.testing.TestTenants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.graphql.test.tester.HttpGraphQlTester
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * De centrale gate voor payment-direct: met de flag uit (voor alle tenants, of alleen aan voor een andere tenant) zijn de query, de mutatie
 * en de postsale-webhook niet beschikbaar. De webhook is een publiek endpoint dat de bank aanroept zonder burger-authenticatie; ook hij
 * beslist de gate per tenant (de tenant volgt uit de host), dus een uitgeschakelde module antwoordt hem met 503.
 */
@SpringBootTest
@Import(PaymentTestConfiguration::class)
@AutoConfigureHttpGraphQlTester
@AutoConfigureWebTestClient(timeout = "36000")
internal class PaymentDirectModuleGateIT(
    @Autowired private val httpGraphQlTester: HttpGraphQlTester,
    @Autowired private val webTestClient: WebTestClient,
    @Autowired private val moduleGate: ModuleGate,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun featureFlags(registry: DynamicPropertyRegistry) = PaymentFlagFile.registreer(registry)

        private const val QUERY = """query { getDirectPaymentStatus(identifier: "belastingzaken", hostedCheckoutId: "4373041363") { status } }"""
        private const val MUTATIE =
            """mutation { doDirectPayment(paymentRequest: { identifier: "belastingzaken", amount: 100.25, orderId: "123456", reference: "12345" }) { redirectUrl } }"""
        private const val WEBHOOK = "/api/public/payment/direct/postsale"
    }

    @AfterEach
    internal fun herstelFlag() {
        PaymentFlagFile.vlaggen.herstel(moduleGate)
    }

    private fun zetUit() = PaymentFlagFile.vlaggen.zet(PAYMENT_DIRECT_MODULE, aan = false, gate = moduleGate)

    private fun zetAlleenAanVoorAndereTenant() = PaymentFlagFile.vlaggen.zet(PAYMENT_DIRECT_MODULE, aan = true, gate = moduleGate, alleenVoor = setOf(TestTenants.WIJDE_VELD))

    private fun assertNietBeschikbaar(document: String) {
        httpGraphQlTester
            .document(document)
            .execute()
            .errors()
            .expect { it.message == "Module 'payment-direct' is niet beschikbaar" && it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    private fun webhookStatus() =
        webTestClient
            .post()
            .uri(WEBHOOK)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()

    @Test
    @WithBurgerUser("123")
    fun `de status-query is niet beschikbaar als de modulevlag uit staat`() {
        zetUit()
        assertNietBeschikbaar(QUERY)
    }

    @Test
    @WithBurgerUser("123")
    fun `de betaalmutatie is niet beschikbaar als de modulevlag uit staat`() {
        zetUit()
        assertNietBeschikbaar(MUTATIE)
    }

    @Test
    fun `de postsale-webhook antwoordt 503 als de modulevlag uit staat`() {
        zetUit()
        webhookStatus().expectStatus().isEqualTo(503)
    }

    @Test
    @WithBurgerUser("123")
    fun `de query is niet beschikbaar voor een tenant waarvoor de vlag uit staat`() {
        zetAlleenAanVoorAndereTenant()
        assertNietBeschikbaar(QUERY)
    }

    @Test
    fun `de postsale-webhook antwoordt 503 voor een tenant waarvoor de vlag uit staat`() {
        zetAlleenAanVoorAndereTenant()
        webhookStatus().expectStatus().isEqualTo(503)
    }
}