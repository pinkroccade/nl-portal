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
package nl.nlportal.zakenapi.client

import nl.nlportal.core.koppeling.KoppelingClient
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.ResultPage
import nl.nlportal.core.koppeling.get
import nl.nlportal.core.koppeling.getAllPages
import nl.nlportal.core.koppeling.getPage
import nl.nlportal.core.koppeling.post
import nl.nlportal.core.modulestatus.ModuleStatusRegistry
import nl.nlportal.core.modulestatus.Oorzaak
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.zakenapi.client.request.ZaakInformatieobjecten
import nl.nlportal.zakenapi.client.request.ZaakObjecten
import nl.nlportal.zakenapi.client.request.ZaakObjectenImpl
import nl.nlportal.zakenapi.client.request.ZaakResultaten
import nl.nlportal.zakenapi.client.request.ZaakResultatenImpl
import nl.nlportal.zakenapi.client.request.ZaakRollen
import nl.nlportal.zakenapi.client.request.ZaakRollenImpl
import nl.nlportal.zakenapi.client.request.ZaakStatussen
import nl.nlportal.zakenapi.client.request.ZaakStatussenImpl
import nl.nlportal.zakenapi.client.request.ZaakSubStatussen
import nl.nlportal.zakenapi.client.request.ZaakSubStatussenImpl
import nl.nlportal.zakenapi.client.request.Zaken
import nl.nlportal.zakenapi.client.request.ZakenImpl
import nl.nlportal.zakenapi.client.request.ZakenInformatieobjectenImpl
import nl.nlportal.zakenapi.client.request.ZoekenImpl
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * De Zaken API van de huidige tenant. De bron (URL en authenticatie) komt per aanroep uit het [KoppelingRegister] en de
 * modulegegevens uit het tenantbestand ([ZakenApiConfiguratie]); beide volgen de tenant uit de context, dus een aanroep
 * gaat nooit naar de bron van een andere tenant. Er wordt niets per tenant bewaard.
 */
class ZakenApiClient(
    private val register: KoppelingRegister,
    private val directory: TenantDirectory,
    private val zaaktypeUrl: (UUID) -> String,
    private val moduleStatus: ModuleStatusRegistry = ModuleStatusRegistry(),
) {
    /** De modulegegevens van de huidige tenant. */
    suspend fun configuratie(): ZakenApiConfiguratie = directory.module()

    /** De URL van een zaak in de Zaken API van de huidige tenant, voor een filter in een volgende aanroep. */
    suspend fun getZaakUrl(zaakId: UUID): String = koppeling().url("$ZAKEN_PAD/zaken/$zaakId")

    /** De URL van een zaaktype in de Catalogi API, voor een filterwaarde; de Zaken API kent de configuratie van de Catalogi API niet. */
    fun getZaakTypeUrl(zaakTypeId: UUID): String = zaaktypeUrl(zaakTypeId)

    suspend inline fun <reified T : Any> haal(
        pad: String,
        query: Map<String, List<String>> = emptyMap(),
    ): T = uitvoeren { it.get<T>(pad, query, CRS_HEADERS) }

    suspend inline fun <reified T : Any> haalPagina(
        pad: String,
        query: Map<String, List<String>> = emptyMap(),
    ): ResultPage<T> = uitvoeren { it.getPage<T>(pad, query, CRS_HEADERS) }

    /** Alle pagina's; de volgende pagina wordt alleen gevolgd onder de basis-URL van de koppeling. */
    suspend inline fun <reified T : Any> haalAlles(
        pad: String,
        query: Map<String, List<String>> = emptyMap(),
    ): List<T> = uitvoeren { it.getAllPages<T>(pad, query, CRS_HEADERS) }

    suspend inline fun <reified T : Any> zoek(
        pad: String,
        body: Any,
        query: Map<String, List<String>> = emptyMap(),
    ): ResultPage<T> = uitvoeren { it.post<Any, ResultPage<T>>(pad, body, CRS_HEADERS + JSON_HEADERS, query) }

    /**
     * Voert [aanroep] uit op de koppeling van de huidige tenant en legt de uitkomst vast als passieve modulestatus. Een 404 en
     * een 401 van de bron komen als [ResponseStatusException] bij de aanroeper, zoals voor de migratie.
     */
    @PublishedApi
    internal suspend fun <T> uitvoeren(aanroep: suspend (KoppelingClient) -> T): T {
        val koppeling = koppeling()
        return try {
            aanroep(koppeling).also { moduleStatus.recordSuccess(MODULE, API) }
        } catch (e: KoppelingException) {
            if (bronBereikbaar(e)) moduleStatus.recordSuccess(MODULE, API) else moduleStatus.recordFailure(MODULE, API, Oorzaak.UNREACHABLE)
            throw naarResponseStatus(e) ?: e
        }
    }

    // De client wordt per aanroep opgehaald en nooit bewaard: hij hoort bij één tenant.
    private suspend fun koppeling(): KoppelingClient =
        try {
            register.current(MODULE)
        } catch (e: ModuleNotConfiguredException) {
            moduleStatus.recordFailure(MODULE, API, Oorzaak.NOT_CONFIGURED)
            throw e
        }

    /** Een 4xx van de bron betekent dat de bron antwoordt; alles anders (netwerk, timeout, 5xx, limiet, open breaker) is een storing. */
    private fun bronBereikbaar(e: KoppelingException) = e.soort in BRON_ANTWOORDT && e.status in 400..499

    private fun naarResponseStatus(e: KoppelingException): ResponseStatusException? =
        when {
            e.soort !in BRON_ANTWOORDT -> null
            e.status == HttpStatus.NOT_FOUND.value() -> ResponseStatusException(HttpStatus.NOT_FOUND)
            e.status == HttpStatus.UNAUTHORIZED.value() -> ResponseStatusException(HttpStatus.UNAUTHORIZED)
            else -> null
        }

    fun zaken(): Zaken = ZakenImpl(this)

    fun zoeken(): Zaken = ZoekenImpl(this)

    fun zaakRollen(): ZaakRollen = ZaakRollenImpl(this)

    fun zaakObjecten(): ZaakObjecten = ZaakObjectenImpl(this)

    fun zaakInformatieobjecten(): ZaakInformatieobjecten = ZakenInformatieobjectenImpl(this)

    fun zaakStatussen(): ZaakStatussen = ZaakStatussenImpl(this)

    fun zaakResultaten(): ZaakResultaten = ZaakResultatenImpl(this)

    fun zaakSubStatussen(): ZaakSubStatussen = ZaakSubStatussenImpl(this)

    suspend fun useNnpKvkQueryIdentificators(): Boolean = configuratie().nnpKvkQueryIdentificatoren

    companion object {
        /** Modulenaam = sleutel in `nl-portal.config` en naam van de koppeling in het tenantbestand. */
        const val MODULE = "zakenapi"
        private const val API = "zakenapi"
        private val BRON_ANTWOORDT = setOf(KoppelingFoutsoort.BRON_PROBLEEM, KoppelingFoutsoort.BRON_NIET_CONFORM)

        /** Wat de Zaken API vraagt voor de coördinaten van geometrie; stond voorheen als standaardheader op de client. */
        @PublishedApi
        internal val CRS_HEADERS = mapOf("Accept-Crs" to "EPSG:4326", "Content-Crs" to "EPSG:4326")

        /** Een zoekvraag is JSON; zonder deze header stuurt een formuliermap een formulier. */
        @PublishedApi
        internal val JSON_HEADERS = mapOf("Content-Type" to "application/json", "Accept" to "application/json")

        const val ZAKEN_PAD = "/zaken/api/v1"
    }
}