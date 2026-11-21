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
package nl.nlportal.core.koppeling

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingleOrNull
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.codec.DecodingException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.UnsupportedMediaTypeException
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
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
            if (++pagina > maxPaginas) throw fout(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR, null)
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
    ): T = uitvoeren(HttpMethod.POST, pad, emptyMap(), headers, body) { it.bodyToMono(type) }

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

    private suspend fun <T : Any> uitvoeren(
        methode: HttpMethod,
        pad: String,
        query: Map<String, List<String>>,
        headers: Map<String, String>,
        body: Any?,
        succes: (ClientResponse) -> Mono<T>,
    ): T {
        // Een client hoort bij één tenant: een bewaarde client mag nooit voor een andere tenant aanroepen.
        check(TenantContext.current() == oin) { "Koppeling '$naam' hoort bij een andere tenant dan de huidige" }
        val verboden = if (eigenHeader == null) VERBODEN_HEADERS else VERBODEN_HEADERS + eigenHeader.lowercase()
        headers.keys.firstOrNull { it.lowercase() in verboden }?.let {
            throw IllegalArgumentException("Header '$it' hoort bij de koppeling en kan niet per aanroep worden gezet")
        }
        val doel = bind(pad, query)
        val aanroep =
            webClient
                .method(methode)
                .uri(doel)
                .headers { koppen -> headers.forEach { (naam, waarde) -> koppen.set(naam, waarde) } }
                .let { if (body != null) it.bodyValue(body) else it }
                .exchangeToMono { antwoord -> if (antwoord.statusCode().is2xxSuccessful) succes(antwoord) else foutAntwoord(antwoord) }
                .timeout(bewaker.timeout)
                .onErrorMap { zetOm(it) }
        return bewaker.beschermd(aanroep).awaitSingleOrNull() ?: throw fout(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR, null)
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

    private fun fout(
        soort: KoppelingFoutsoort,
        oorzaak: Throwable?,
    ) = KoppelingException.gesynthetiseerd(naam, soort, cause = oorzaak)

    private companion object {
        val logger = KotlinLogging.logger {}
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        val PROBLEM_JSON: MediaType = MediaType.APPLICATION_PROBLEM_JSON
        val VERBODEN_HEADERS =
            setOf(HttpHeaders.AUTHORIZATION, HttpHeaders.PROXY_AUTHORIZATION, HttpHeaders.COOKIE, HttpHeaders.HOST).map { it.lowercase() }.toSet()
        const val MAX_PAGINAS = 100
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
): T = post(pad, body, typeReferentie<T>(), headers)

/** De typereferentie voor [T], met de generieke parameters bewaard (`ResultPage<Zaak>`). */
inline fun <reified T : Any> typeReferentie(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}