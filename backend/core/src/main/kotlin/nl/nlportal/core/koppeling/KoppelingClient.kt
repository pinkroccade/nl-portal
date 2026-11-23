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
package nl.nlportal.core.koppeling

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.UnsupportedMediaTypeException
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.concurrent.TimeoutException

/**
 * De diepe client voor één koppeling van één tenant: generiek ophalen en muteren met de authenticatie van die
 * koppeling, één foutvorm ([KoppelingException], RFC 9457) en één pagineringsvorm ([ResultPage]).
 *
 * Een [WebClient] komt niet buiten deze klasse. Een pad of een URL die de bron teruggaf ([ResultPage.next]) wordt
 * alleen gevolgd onder de basis-URL van de koppeling (zie [HostBinding]); er worden geen headers of bodies gelogd.
 * Een body is JSON (`Content-Type: application/json`), tenzij de aanroeper per aanroep zelf een content-type meegeeft (bijvoorbeeld
 * `application/merge-patch+json`); bytes en streaming bouwt T14 als eigen pad.
 * Verkrijg een client via [KoppelingRegister.current].
 */
class KoppelingClient internal constructor(
    val naam: String,
    private val oin: Oin,
    private val basis: URI,
    private val webClient: WebClient,
    private val bewaker: KoppelingBewaker,
    private val eigenHeader: String? = null,
) {
    suspend fun <T : Any> get(
        pad: String,
        type: ParameterizedTypeReference<T>,
        query: Map<String, List<String>> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T = uitvoeren(HttpMethod.GET, pad, query, headers, null) { it.bodyToMono(type) }

    /** Eén pagina. Volg [ResultPage.next] met nogmaals deze functie; een URL buiten de basis-URL wordt geweigerd. */
    suspend fun <T : Any> getPage(
        pad: String,
        type: ParameterizedTypeReference<ResultPage<T>>,
        query: Map<String, List<String>> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): ResultPage<T> = get(pad, type, query, headers)

    /** Alle pagina's samen; stopt met een fout na [maxPaginas] pagina's, zodat een bron niet eindeloos kan doorverwijzen. */
    suspend fun <T : Any> getAllPages(
        pad: String,
        type: ParameterizedTypeReference<ResultPage<T>>,
        query: Map<String, List<String>> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        maxPaginas: Int = MAX_PAGINAS,
    ): List<T> {
        val resultaat = mutableListOf<T>()
        var volgende: String? = pad
        var pagina = 0
        while (volgende != null) {
            if (++pagina > maxPaginas) throw paginaLimietBereikt()
            val antwoord = getPage(volgende, type, if (pagina == 1) query else emptyMap(), headers)
            resultaat += antwoord.results
            volgende = antwoord.next
        }
        return resultaat
    }

    suspend fun <B : Any, T : Any> post(
        pad: String,
        body: B,
        type: ParameterizedTypeReference<T>,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, List<String>> = emptyMap(),
    ): T = uitvoeren(HttpMethod.POST, pad, query, headers, body) { it.bodyToMono(type) }

    /**
     * De absolute URL van [pad] onder de basis-URL van deze koppeling, bijvoorbeeld als waarde van een filter (`zaak=<URL>`).
     * Een pad of URL buiten de basis-URL wordt geweigerd met een [KoppelingException] (zie [HostBinding]).
     */
    fun url(pad: String): String = bind(pad, emptyMap()).toString()

    suspend fun <B : Any, T : Any> put(
        pad: String,
        body: B,
        type: ParameterizedTypeReference<T>,
        headers: Map<String, String> = emptyMap(),
    ): T = uitvoeren(HttpMethod.PUT, pad, emptyMap(), headers, body) { it.bodyToMono(type) }

    suspend fun <B : Any, T : Any> patch(
        pad: String,
        body: B,
        type: ParameterizedTypeReference<T>,
        headers: Map<String, String> = emptyMap(),
    ): T = uitvoeren(HttpMethod.PATCH, pad, emptyMap(), headers, body) { it.bodyToMono(type) }

    suspend fun delete(
        pad: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        uitvoeren(HttpMethod.DELETE, pad, emptyMap(), headers, null) { it.releaseBody().thenReturn(Unit) }
    }

    /**
     * Een groot antwoord (een document) als stroom van [DataBuffer]s, zonder het geheel in het geheugen te laden. Dezelfde garanties
     * als een gewone aanroep: tenantcontrole, host-binding, geen redirects, geen eigen autorisatieheaders en één foutvorm (een foutstatus
     * wordt een [KoppelingException] zonder de body van de bron).
     *
     * Een stroom heeft een eigen limiet (`maxGelijktijdigeStromen`) naast die van de metadata-aanroepen. De circuit breaker en de
     * timeout beoordelen de bron tot en met de eerste respons; daarna geldt alleen een stiltetimeout tussen twee brokken (ook als de
     * afnemer niet afneemt). Zo'n timeout of een annulering telt niet als storing van de bron; de plek in de limiet komt wel vrij.
     *
     * Een buffer die Reactor nog vasthoudt bij annuleren of een fout wordt vrijgegeven. Tot [STROOM_BUFFER] brokken in de brug naar de
     * [Flow] gaan bij annuleren verloren zonder vrijgave (vervolgwerk). De aanroeper geeft elke ontvangen buffer vrij
     * (bijvoorbeeld met `DataBufferUtils.release`) of geeft de stroom door aan een antwoord dat dat doet.
     */
    suspend fun getStream(
        pad: String,
        accept: MediaType? = null,
        query: Map<String, List<String>> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Flow<DataBuffer> {
        val doel = voorbereiden(pad, query, headers)
        val stroom =
            bewaker.beschermdStroom { fase ->
                val respons = Sinks.empty<Void>()
                webClient
                    .get()
                    .uri(doel)
                    .headers { koppen -> headers.forEach { (naam, waarde) -> koppen.set(naam, waarde) } }
                    // Altijd een eigen Accept: een standaard van de koppeling (application/json) past niet bij een download.
                    .accept(accept ?: MediaType.ALL)
                    .exchangeToFlux { antwoord ->
                        if (antwoord.statusCode().is2xxSuccessful) {
                            fase.koptekstOntvangen()
                            respons.tryEmitEmpty()
                            antwoord.bodyToFlux(DataBuffer::class.java)
                        } else {
                            foutAntwoord<DataBuffer>(antwoord).flux()
                        }
                    }.timeout(eersteResponsTimeout(respons.asMono())) { Mono.delay(bewaker.timeout) }
                    .doOnDiscard(DataBuffer::class.java) { DataBufferUtils.release(it) }
                    .onErrorMap { zetOm(it) }
            }
        return stroom.asFlow().buffer(STROOM_BUFFER)
    }

    /** Eerst de tijd tot de eerste respons, daarna de stilte tot het eerste brok; de timeout van de eerste respons loopt niet door. */
    private fun eersteResponsTimeout(respons: Mono<Void>): Mono<Long> =
        Mono
            .firstWithSignal(
                Mono.delay(bewaker.timeout).map { true },
                respons.then(Mono.just(false)),
            ).flatMap { teLaat -> if (teLaat) Mono.just(0L) else Mono.delay(bewaker.timeout) }

    /** Of [url] een absolute URL onder de basis-URL van deze koppeling is (host-binding); er wordt niets aangeroepen. */
    fun beheertUrl(url: String): Boolean {
        val absoluut = runCatching { URI(url).isAbsolute }.getOrDefault(false)
        return absoluut && runCatching { HostBinding.oplossen(basis, url) }.isSuccess
    }

    private suspend fun <T : Any> uitvoeren(
        methode: HttpMethod,
        pad: String,
        query: Map<String, List<String>>,
        headers: Map<String, String>,
        body: Any?,
        succes: (ClientResponse) -> Mono<T>,
    ): T {
        val doel = voorbereiden(pad, query, headers)
        val aanroep =
            webClient
                .method(methode)
                .uri(doel)
                .headers { koppen ->
                    headers.forEach { (naam, waarde) -> koppen.set(naam, waarde) }
                    // Een body is json, tenzij de aanroeper zelf een content-type geeft. Vooraf gezet: een koppeling die verzoeken
                    // ondertekent (gcs-v1-hmac) tekent het content-type mee, en dat staat anders pas na het filter op het verzoek.
                    if (body != null && koppen.contentType == null) koppen.contentType = MediaType.APPLICATION_JSON
                }.let { if (body != null) it.bodyValue(body) else it }
                .exchangeToMono { antwoord -> if (antwoord.statusCode().is2xxSuccessful) succes(antwoord) else foutAntwoord(antwoord) }
                .timeout(bewaker.timeout)
                .onErrorMap { zetOm(it) }
        return bewaker.beschermd(aanroep).awaitSingleOrNull() ?: throw fout(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR, null)
    }

    /** De controles vooraf, gedeeld door elke aanroep: de tenant, de headers en de host-binding; geeft het doel. */
    private suspend fun voorbereiden(
        pad: String,
        query: Map<String, List<String>>,
        headers: Map<String, String>,
    ): URI {
        // Een client hoort bij één tenant: een bewaarde client mag nooit voor een andere tenant aanroepen.
        check(TenantContext.current() == oin) { "Koppeling '$naam' hoort bij een andere tenant dan de huidige" }
        val verboden = if (eigenHeader == null) VERBODEN_HEADERS else VERBODEN_HEADERS + eigenHeader.lowercase()
        headers.keys.firstOrNull { it.lowercase() in verboden }?.let {
            throw KoppelingConfiguratieException("Header '$it' hoort bij de koppeling en kan niet per aanroep worden gezet")
        }
        return bind(pad, query)
    }

    private fun bind(
        pad: String,
        query: Map<String, List<String>>,
    ): URI =
        try {
            HostBinding.oplossen(basis, pad, query)
        } catch (e: IllegalArgumentException) {
            // De reden noemt nooit de URL zelf: een door de bron aangeleverde URL kan een geheim bevatten.
            logger.warn { "Koppeling '$naam': URL geweigerd (${e.message})" }
            throw fout(KoppelingFoutsoort.HOST_BINDING, e)
        }

    private fun <T : Any> foutAntwoord(antwoord: ClientResponse): Mono<T> {
        val status = antwoord.statusCode()
        val contentType = antwoord.headers().contentType().orElse(null)
        return antwoord
            .bodyToMono(ByteArray::class.java)
            .defaultIfEmpty(ByteArray(0))
            .onErrorReturn(ByteArray(0))
            .flatMap { Mono.error(bronFout(status, contentType, it)) }
    }

    private fun bronFout(
        status: HttpStatusCode,
        contentType: MediaType?,
        body: ByteArray,
    ): KoppelingException {
        val probleem = if (contentType != null && PROBLEM_JSON.isCompatibleWith(contentType)) parseProbleem(body) else null
        val fout =
            if (probleem != null) {
                KoppelingException(naam, KoppelingFoutsoort.BRON_PROBLEEM, probleem)
            } else {
                // Een 4xx blijft zichtbaar voor de module (bijvoorbeeld 404); alles anders is voor ons een slechte bron.
                val eigen = if (status.is4xxClientError) status.value() else KoppelingFoutsoort.BRON_NIET_CONFORM.status
                KoppelingException.gesynthetiseerd(naam, KoppelingFoutsoort.BRON_NIET_CONFORM, eigen)
            }
        logger.warn { "Koppeling '$naam': bron antwoordde ${status.value()} (${fout.soort}, referentie ${fout.correlatieId})" }
        return fout
    }

    /** Geldig als het een JSON-object is met een numerieke `status` (400-599) en tekstwaarden voor `type`, `title`, `detail` en `instance`. */
    private fun parseProbleem(body: ByteArray): KoppelingProblem? =
        runCatching {
            val knoop = MAPPER.readTree(body)
            if (!knoop.isObject) return@runCatching null
            val status = knoop.veld("status") { it.isInt }?.intValue()
            if (status == null || status !in 400..599) return@runCatching null
            KoppelingProblem(
                type = knoop.veld("type") { it.isString }?.stringValue()?.let { URI.create(it) } ?: URI.create("about:blank"),
                title = knoop.veld("title") { it.isString }?.stringValue() ?: "Onbekend probleem",
                status = status,
                detail = knoop.veld("detail") { it.isString }?.stringValue(),
                instance = knoop.veld("instance") { it.isString }?.stringValue()?.let { URI.create(it) },
            )
        }.getOrNull()

    private fun JsonNode.veld(
        veld: String,
        geldig: (JsonNode) -> Boolean,
    ): JsonNode? = get(veld)?.takeIf { !it.isNull }?.also { require(geldig(it)) { "ongeldig veld" } }

    private fun zetOm(fout: Throwable): Throwable =
        when (fout) {
            is KoppelingException -> {
                fout
            }

            is TimeoutException -> {
                metLog(fout(KoppelingFoutsoort.TIMEOUT, fout))
            }

            is JacksonException, is DecodingException, is UnsupportedMediaTypeException -> {
                metLog(fout(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR, fout))
            }

            else -> {
                metLog(fout(KoppelingFoutsoort.NETWERK, fout))
            }
        }

    private fun metLog(fout: KoppelingException): KoppelingException {
        logger.warn { "Koppeling '$naam': ${fout.soort} (referentie ${fout.correlatieId})" }
        return fout
    }

    /** De fout voor een bron die meer dan [MAX_PAGINAS] pagina's blijft geven; ook voor een aanroeper die zelf over pagina's loopt. */
    fun paginaLimietBereikt(): KoppelingException = fout(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR, null)

    private fun fout(
        soort: KoppelingFoutsoort,
        oorzaak: Throwable?,
    ) = KoppelingException.gesynthetiseerd(naam, soort, cause = oorzaak)

    companion object {
        /** Het maximum aantal pagina's dat [getAllPages] volgt; een bron die blijft doorverwijzen geeft daarna een fout. */
        const val MAX_PAGINAS = 100

        /** Brokken die tussen de bron en de afnemer mogen wachten; klein, want bij annuleren gaan ze verloren. */
        const val STROOM_BUFFER = 4

        private val logger = KotlinLogging.logger {}
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
        private val PROBLEM_JSON: MediaType = MediaType.APPLICATION_PROBLEM_JSON
        private val VERBODEN_HEADERS =
            setOf(HttpHeaders.AUTHORIZATION, HttpHeaders.PROXY_AUTHORIZATION, HttpHeaders.COOKIE, HttpHeaders.HOST).map { it.lowercase() }.toSet()
    }
}

/** Gemak zonder typereferentie: roept alleen de functie met een typereferentie aan, zodat er geen `WebClient` in de module komt. */
suspend inline fun <reified T : Any> KoppelingClient.get(
    pad: String,
    query: Map<String, List<String>> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
): T = get(pad, typeReferentie<T>(), query, headers)

suspend inline fun <reified T : Any> KoppelingClient.getPage(
    pad: String,
    query: Map<String, List<String>> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
): ResultPage<T> = getPage(pad, typeReferentie<ResultPage<T>>(), query, headers)

suspend inline fun <reified T : Any> KoppelingClient.getAllPages(
    pad: String,
    query: Map<String, List<String>> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
): List<T> = getAllPages(pad, typeReferentie<ResultPage<T>>(), query, headers)

suspend inline fun <reified B : Any, reified T : Any> KoppelingClient.post(
    pad: String,
    body: B,
    headers: Map<String, String> = emptyMap(),
    query: Map<String, List<String>> = emptyMap(),
): T = post(pad, body, typeReferentie<T>(), headers, query)

/** De typereferentie voor [T], met de generieke parameters bewaard (`ResultPage<Zaak>`). */
inline fun <reified T : Any> typeReferentie(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}