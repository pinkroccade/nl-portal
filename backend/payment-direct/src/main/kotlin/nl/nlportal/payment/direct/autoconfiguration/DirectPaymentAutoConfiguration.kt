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
package nl.nlportal.payment.direct.autoconfiguration

import nl.nlportal.core.koppeling.KoppelingAutoConfiguration
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenancyAutoConfiguration
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.VervallenProperties
import nl.nlportal.payment.direct.api.DirectPaymentController
import nl.nlportal.payment.direct.client.DirectPaymentClient
import nl.nlportal.payment.direct.configuratie.DirectPaymentTenantConfiguratie
import nl.nlportal.payment.direct.graphql.DirectPaymentMutation
import nl.nlportal.payment.direct.graphql.DirectPaymentQuery
import nl.nlportal.payment.direct.service.DirectPaymentService
import nl.nlportal.payment.direct.service.DirectPaymentWebhookService
import nl.nlportal.payment.direct.service.WebhookHandtekeningen
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * De module leest zijn gegevens per tenant uit `modules.payment-direct` en praat alleen via de [KoppelingRegister]; of de module voor
 * een tenant bruikbaar is volgt uit die configuratie (en de permission-flag, T22), niet uit een eigenschap van de applicatie.
 */
@AutoConfiguration(after = [TenancyAutoConfiguration::class, KoppelingAutoConfiguration::class])
@ConditionalOnBean(KoppelingRegister::class)
class DirectPaymentAutoConfiguration {
    /** Ook `enabled` is vervallen: de hele oude prefix weigert het opstarten, met een verwijzing naar `modules.payment-direct`. */
    @Bean
    fun directPaymentVervallenProperties() = VervallenProperties(DirectPaymentTenantConfiguratie.NAAM, "nl-portal.config.payment.direct")

    @Bean
    @ConditionalOnMissingBean(DirectPaymentClient::class)
    fun directPaymentClient(register: KoppelingRegister): DirectPaymentClient = DirectPaymentClient(register)

    @Bean
    @ConditionalOnMissingBean(DirectPaymentService::class)
    fun directPaymentService(
        directory: TenantDirectory,
        directPaymentClient: DirectPaymentClient,
    ): DirectPaymentService = DirectPaymentService(directory, directPaymentClient)

    @Bean
    @ConditionalOnMissingBean(WebhookHandtekeningen::class)
    fun webhookHandtekeningen(
        directory: TenantDirectory,
        bron: GeheimenBron,
    ): WebhookHandtekeningen = WebhookHandtekeningen(directory, bron)

    @Bean
    @ConditionalOnMissingBean(DirectPaymentWebhookService::class)
    @ConditionalOnProperty(prefix = "nl-portal.config", name = ["objectenapi.enabled"], havingValue = "true")
    fun directPaymentWebhookService(
        handtekeningen: WebhookHandtekeningen,
        objectsApiClient: ObjectsApiClient,
    ): DirectPaymentWebhookService = DirectPaymentWebhookService(handtekeningen, objectsApiClient)

    @Bean
    fun directPaymentMutation(directPaymentService: DirectPaymentService): DirectPaymentMutation = DirectPaymentMutation(directPaymentService)

    @Bean
    fun directPaymentQuery(directPaymentService: DirectPaymentService): DirectPaymentQuery = DirectPaymentQuery(directPaymentService)

    @Bean
    @ConditionalOnMissingBean(DirectPaymentController::class)
    @ConditionalOnProperty(prefix = "nl-portal.config", name = ["objectenapi.enabled"], havingValue = "true")
    fun directPaymentController(directPaymentWebhookService: DirectPaymentWebhookService): DirectPaymentController = DirectPaymentController(directPaymentWebhookService)
}