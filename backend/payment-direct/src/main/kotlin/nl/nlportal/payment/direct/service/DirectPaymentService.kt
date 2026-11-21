/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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

import com.onlinepayments.domain.AmountOfMoney
import com.onlinepayments.domain.CardPaymentMethodSpecificInputBase
import com.onlinepayments.domain.CreateHostedCheckoutRequest
import com.onlinepayments.domain.Feedbacks
import com.onlinepayments.domain.HostedCheckoutSpecificInput
import com.onlinepayments.domain.Order
import com.onlinepayments.domain.OrderReferences
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.payment.direct.client.DirectPaymentClient
import nl.nlportal.payment.direct.configuratie.DirectPaymentConfiguratie
import nl.nlportal.payment.direct.configuratie.DirectPaymentProfiel
import nl.nlportal.payment.direct.domain.DirectPaymentRequest
import nl.nlportal.payment.direct.domain.DirectPaymentResponse
import nl.nlportal.payment.direct.domain.DirectPaymentStatus
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

open class DirectPaymentService(
    private val directory: TenantDirectory,
    private val directPaymentClient: DirectPaymentClient,
) {
    /**
     * Het profiel met deze identifier, uit de configuratie van de huidige tenant. De identifier is vrije invoer van de client en
     * staat daarom nooit in de melding of in de log.
     */
    private fun profiel(
        configuratie: DirectPaymentConfiguratie,
        identificatie: String,
    ): DirectPaymentProfiel = configuratie.profiel(identificatie) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Onbekend betaalprofiel")

    suspend fun getDirectPaymentStatus(
        identifier: String,
        hostedCheckoutId: String,
    ): DirectPaymentStatus {
        // Fail-closed: zonder tenant of zonder moduleconfiguratie volgt een fout.
        val paymentDirectProfile = profiel(directory.module<DirectPaymentConfiguratie>(), identifier)

        val directPaymentStatusResponse =
            directPaymentClient.hostedCheckoutStatus(
                profiel = paymentDirectProfile,
                hostedCheckoutId = hostedCheckoutId,
            )

        return DirectPaymentStatus(
            status = directPaymentStatusResponse.createdPaymentOutput.paymentStatusCategory,
        )
    }

    /**
     * Do Direct Payment at payment provider
     * @param paymentRequest: properties to create a payment
     * @return redirectUrl to do the actual payment at payment provider
     */
    suspend fun doDirectPayment(paymentRequest: DirectPaymentRequest): DirectPaymentResponse {
        try {
            val configuratie = directory.module<DirectPaymentConfiguratie>()
            val paymentDirectProfile = profiel(configuratie, paymentRequest.identifier)

            val hostedCheckoutSpecificInput =
                HostedCheckoutSpecificInput()
                    .withLocale(paymentRequest.langId ?: paymentDirectProfile.taal)
                    .withReturnUrl(paymentRequest.returnUrl ?: paymentDirectProfile.returnUrl)
                    .withShowResultPage(configuratie.toonResultaatPagina)
                    .withAllowedNumberOfPaymentAttempts(paymentDirectProfile.aantalPogingen)

            configuratie.customTemplateUrl?.let {
                hostedCheckoutSpecificInput.variant = it
            }

            val checkoutRequest =
                CreateHostedCheckoutRequest()
                    .withOrder(
                        Order()
                            .withAmountOfMoney(
                                AmountOfMoney()
                                    .withAmount((paymentRequest.amount * 100).toLong())
                                    .withCurrencyCode(paymentDirectProfile.valuta),
                            ).withReferences(
                                OrderReferences()
                                    .withDescriptor(paymentRequest.reference)
                                    .withMerchantReference(paymentRequest.orderId),
                            ),
                    ).withHostedCheckoutSpecificInput(
                        hostedCheckoutSpecificInput,
                    )

            // Kaartbetalingen alleen als het profiel creditcardAan heeft.
            if (paymentDirectProfile.creditcardAan) {
                checkoutRequest.withCardPaymentMethodSpecificInput(
                    CardPaymentMethodSpecificInputBase()
                        .withAuthorizationMode("SALE"),
                )
            }

            // Is webhookUrl in de configuratie gezet, dan gaat die mee in het verzoek, zodat de webhook per omgeving een eigen adres heeft.
            configuratie.webhookUrl?.let {
                checkoutRequest.withFeedbacks(
                    Feedbacks().withWebhooksUrls(listOf(it)),
                )
            }

            val response =
                directPaymentClient.hostedCheckout(
                    profiel = paymentDirectProfile,
                    checkoutRequest = checkoutRequest,
                )

            return DirectPaymentResponse(
                redirectUrl = response.redirectUrl,
            )
        } catch (ex: Exception) {
            when (ex) {
                // Nooit het bronantwoord of de oorzaak loggen: alleen koppeling, soort en referentie.
                is KoppelingException -> logger.error { "Betaling starten mislukt: koppeling '${ex.koppeling}', ${ex.soort}, referentie ${ex.correlatieId}" }

                // Bekende fouten: de aanroeper of de GraphQL-foutafhandeling regelt de melding (en de log).
                is ResponseStatusException, is ModuleNotConfiguredException, is TenantContextMissingException -> Unit

                // Alleen het type: het bericht of de stacktrace kan gegevens uit het antwoord of de invoer bevatten.
                else -> logger.error { "Betaling starten mislukt: onverwachte fout (${ex::class.simpleName})" }
            }
            throw ex
        }
    }

    companion object {
        private val logger: KLogger = KotlinLogging.logger {}
    }
}