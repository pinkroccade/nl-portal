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
package nl.nlportal.payment.direct.configuratie

import nl.nlportal.core.tenancy.GeheimVerwijzing
import nl.nlportal.core.tenancy.NietErfbaar

/**
 * De configuratie van payment-direct voor één tenant: `modules.payment-direct` in het tenantbestand. De bron zelf (url en de
 * ondertekening van uitgaande aanroepen) staat onder `koppelingen`, per profiel gekozen via [DirectPaymentProfiel.koppeling].
 */
data class DirectPaymentConfiguratie(
    /** De URL die de betaalprovider aanroept; zet hem in het verzoek zodat hij per omgeving en tenant verschilt. */
    @NietErfbaar val webhookUrl: String? = null,
    @NietErfbaar val customTemplateUrl: String? = null,
    @NietErfbaar val toonResultaatPagina: Boolean = false,
    @NietErfbaar val profielen: Map<String, DirectPaymentProfiel>,
) {
    /** Het profiel met deze naam, anders het profiel met dit PSP-ID. */
    fun profiel(identificatie: String): DirectPaymentProfiel? = profielen[identificatie] ?: profielen.values.firstOrNull { it.pspId == identificatie }
}

/** Eén betaalprofiel (een PSP-ID) van een tenant. */
data class DirectPaymentProfiel(
    val pspId: String,
    val taal: String = "nl_NL",
    val valuta: String = "EUR",
    val returnUrl: String,
    /** De koppeling onder `koppelingen` met de url en de API-sleutel van dit profiel; standaard die met de modulenaam. */
    val koppeling: String? = null,
    /** Het sleutel-ID waarmee de provider de postsale-webhook ondertekent; geen geheim, maar wel van één tenant. */
    @NietErfbaar val webhookSleutelId: String? = null,
    val webhookGeheim: GeheimVerwijzing? = null,
    val creditcardAan: Boolean = false,
    val aantalPogingen: Int = 1,
) {
    fun koppelingNaam(): String = koppeling ?: DirectPaymentTenantConfiguratie.NAAM
}