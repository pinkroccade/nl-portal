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
import nl.nlportal.core.util.requireConfigured
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Bouwt uit een [Koppeling] een [WebClient] met de authenticatie van die koppeling; alleen het register gebruikt hem (epic #499, #507). */
internal class KoppelingWebClientFactory(
    private val webClientBuilder: WebClient.Builder,
    private val zgwJwtGenerator: ZgwJwtGenerator = ZgwJwtGenerator(),
    private val klok: Clock = Clock.systemUTC(),
) {
    /**
     * Elke client van een koppeling krijgt een eigen connector die redirects nooit volgt. Dat staat hier in code, niet in
     * configuratie: Spring Boot volgt anders standaard redirects (`spring.http.clients.redirects`), en dan geldt de host-binding
     * alleen voor de eerste URL en lekken credentials naar het doel van de redirect. Het gevolg is dat de connector-instellingen
     * van de door Spring Boot geleverde builder (bijvoorbeeld een SSL-bundel) niet gelden voor koppelingen.
     */
    private val zonderRedirects = ReactorClientHttpConnector(HttpClient.create().followRedirect(false))

    private fun basisBuilder() = webClientBuilder.clone().clientConnector(zonderRedirects)

    fun webClient(
        modulenaam: String,
        koppeling: Koppeling,
    ): WebClient {
        val url = koppeling.url.requireConfigured(modulenaam, "url")
        // De oude clients vroegen om JSON; een strikte bron of de gateway kan zonder Accept anders antwoorden.
        val builder =
            basisBuilder()
                .baseUrl(url)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        if (koppeling.isOnbeveiligd()) {
            logger.warn { "Koppeling van module '$modulenaam' gebruikt http naar een publiek adres; gebruik https." }
        }
        val auth = koppeling.auth
        when (auth.type.requireConfigured(modulenaam, "auth.type")) {
            KoppelingAuthType.GEEN -> {
                // Geen inloggegeven: de cloud gateway authenticeert. Bewust een eigen keuze, geen weggelaten auth.
            }

            KoppelingAuthType.API_KEY -> {
                builder.defaultHeader(
                    auth.headerName.requireConfigured(modulenaam, "auth.header-name"),
                    auth.value.requireConfigured(modulenaam, "auth.value"),
                )
            }

            KoppelingAuthType.BEARER -> {
                builder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer ${auth.token.requireConfigured(modulenaam, "auth.token")}",
                )
            }

            KoppelingAuthType.ZGW_JWT -> {
                // Alle waarden bij het opstarten ophalen: een ontbrekende waarde hoort geen netwerkstoring te zijn.
                val secret = auth.secret.requireConfigured(modulenaam, "auth.secret")
                val clientId = auth.clientId.requireConfigured(modulenaam, "auth.client-id")
                builder.filter(bearer { Mono.fromCallable { zgwJwtGenerator.generateToken(secret, clientId) } })
            }

            KoppelingAuthType.OAUTH2_CLIENT_CREDENTIALS -> {
                val tokenUrl = auth.tokenUrl.requireConfigured(modulenaam, "auth.token-url")
                val basicAuth =
                    auth.clientId.requireConfigured(modulenaam, "auth.client-id") to
                        auth.secret.requireConfigured(modulenaam, "auth.secret")
                builder.filter(bearer(TokenCache { haalToken(tokenUrl, basicAuth = basicAuth) }::token))
            }

            KoppelingAuthType.OAUTH2_PRIVATE_KEY_JWT -> {
                val tokenUrl = auth.tokenUrl.requireConfigured(modulenaam, "auth.token-url")
                val clientId = auth.clientId.requireConfigured(modulenaam, "auth.client-id")
                val sleutel = ClientAssertion.leesSleutel(auth.privateKeyFile.requireConfigured(modulenaam, "auth.private-key-file"))
                builder.filter(
                    bearer(
                        TokenCache {
                            haalToken(
                                tokenUrl,
                                extraVelden =
                                    mapOf(
                                        "client_assertion_type" to ClientAssertion.TYPE,
                                        "client_assertion" to ClientAssertion.sign(clientId, tokenUrl, sleutel),
                                    ),
                            )
                        }::token,
                    ),
                )
            }

            KoppelingAuthType.GCS_V1_HMAC -> {
                val sleutelId = auth.clientId.requireConfigured(modulenaam, "auth.client-id")
                val secret = auth.secret.requireConfigured(modulenaam, "auth.secret")
                builder.filter(gcsHmac(sleutelId, secret, URI(url).rawPath.orEmpty().trimEnd('/')))
            }
        }
        return builder.build()
    }

    /**
     * Ondertekent elk verzoek volgens Worldline Direct (`GCS v1HMAC`), op dezelfde manier als de Worldline-SDK
     * (`V1HmacAuthenticator`): HMAC-SHA256 over methode, content-type (leeg als het ontbreekt), datum, de `X-GCS-*`-headers van het
     * verzoek (naam in kleine letters, waarde met regeleinden vervangen door een spatie en getrimd, gesorteerd op naam) en het pad
     * relatief aan de basis-URL. Het pad blijft gecodeerd, de query is gedecodeerd (zoals `URI.getQuery()` in de SDK).
     * `Date` en `Authorization` worden gezet (vervangen, niet toegevoegd): een `Date` van de aanroeper telt niet.
     */
    private fun gcsHmac(
        sleutelId: String,
        secret: String,
        basisPad: String,
    ) = ExchangeFilterFunction.ofRequestProcessor { verzoek ->
        val datum = GCS_DATUM.format(ZonedDateTime.now(klok).withZoneSameInstant(ZoneOffset.UTC))
        val koppen = verzoek.headers()
        val teOndertekenen =
            (
                listOf(verzoek.method().name(), koppen.getFirst(HttpHeaders.CONTENT_TYPE).orEmpty(), datum) +
                    canoniekeGcsHeaders(koppen) +
                    relatiefPad(verzoek.url(), basisPad)
            ).joinToString("\n", postfix = "\n")
        val handtekening = hmacSha256Base64(secret, teOndertekenen)
        Mono.just(
            ClientRequest
                .from(verzoek)
                .headers {
                    it.set(HttpHeaders.DATE, datum)
                    it.set(HttpHeaders.AUTHORIZATION, "GCS v1HMAC:$sleutelId:$handtekening")
                }.build(),
        )
    }

    /** De `X-GCS-*`-headers als `naam:waarde`: naam in kleine letters, waarde met regeleinden vervangen door een spatie en getrimd, gesorteerd op naam. */
    private fun canoniekeGcsHeaders(koppen: HttpHeaders): List<String> =
        koppen
            .headerNames()
            .flatMap { naam -> koppen[naam].orEmpty().map { naam.lowercase(Locale.ENGLISH) to it.replace(HEADER_REGELEINDE, " ").trim() } }
            .filter { (naam, _) -> naam.startsWith("x-gcs") }
            .sortedBy { (naam, _) -> naam }
            .map { (naam, waarde) -> "$naam:$waarde" }

    /** Het gecodeerde pad zonder het pad van de basis-URL (alleen op een segmentgrens), plus de gedecodeerde query. */
    private fun relatiefPad(
        url: URI,
        basisPad: String,
    ): String {
        val pad = url.rawPath.orEmpty()
        val relatief = if (pad == basisPad || pad.startsWith("$basisPad/")) pad.removePrefix(basisPad) else pad
        return relatief + (url.query?.let { "?$it" } ?: "")
    }

    private fun hmacSha256Base64(
        secret: String,
        tekst: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return Base64.getEncoder().encodeToString(mac.doFinal(tekst.toByteArray()))
    }

    private fun bearer(token: () -> Mono<String>) =
        ExchangeFilterFunction.ofRequestProcessor { verzoek ->
            token().map { ClientRequest.from(verzoek).header(HttpHeaders.AUTHORIZATION, "Bearer $it").build() }
        }

    private fun haalToken(
        tokenUrl: String,
        basicAuth: Pair<String, String>? = null,
        extraVelden: Map<String, String> = emptyMap(),
    ): Mono<AccessToken> {
        val formulier = LinkedMultiValueMap<String, String>().apply { add("grant_type", "client_credentials") }
        extraVelden.forEach { (naam, waarde) -> formulier.add(naam, waarde) }
        return basisBuilder()
            .build()
            .post()
            .uri(tokenUrl)
            .headers { koppen -> basicAuth?.let { koppen.setBasicAuth(it.first, it.second) } }
            .body(BodyInserters.fromFormData(formulier))
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<Map<String, Any>>() {})
            .map { antwoord ->
                val geldigheid = (antwoord["expires_in"] as? Number)?.toLong() ?: DEFAULT_GELDIGHEID_SECONDEN
                AccessToken(
                    antwoord["access_token"] as String,
                    Instant.now().plus(Duration.ofSeconds(geldigheid)).minus(MARGE),
                )
            }
    }

    private class AccessToken(
        val waarde: String,
        val verloopt: Instant,
    )

    /** Hergebruikt het laatste token tot het (min een marge) verloopt. */
    private class TokenCache(
        private val ophalen: () -> Mono<AccessToken>,
    ) {
        @Volatile
        private var token: AccessToken? = null

        fun token(): Mono<String> =
            Mono.defer {
                token?.takeIf { Instant.now().isBefore(it.verloopt) }?.let { Mono.just(it.waarde) }
                    ?: ophalen().doOnNext { token = it }.map { it.waarde }
            }
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** IMF-fixdate (RFC 9110): tweecijferige dag, altijd Engelse namen en `GMT`. */
        val GCS_DATUM: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        val HEADER_REGELEINDE = Regex("\r?\n[\\s&&[^\r\n]]*")
        const val DEFAULT_GELDIGHEID_SECONDEN = 60L
        val MARGE: Duration = Duration.ofSeconds(30)
    }
}