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
package nl.nlportal.commonground.authentication.bff

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.HttpCookie
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Seam 1 van de spec: de volledige app via HTTP. Een testclient speelt de browser na (eigen cookiejar, geen redirects volgen,
 * `Host`-header per tenant) en praat met de draaiende app; de testprovider (`mock-oauth2-server`) bedient een issuer per tenant,
 * laat per test de claims zetten en maakt de binnengekomen client-assertion zichtbaar.
 */
@SpringBootTest(classes = [BffTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
abstract class BffTestBasis {
    @LocalServerPort
    var poort: Int = 0

    private lateinit var client: WebTestClient

    /** Alles wat de app tijdens een test logt, voor de controle dat events kloppen en nooit een BSN bevatten. */
    private val logboek = ListAppender<ILoggingEvent>()

    protected val logregels: List<String> get() = logboek.list.map { it.formattedMessage }

    protected val beveiligingsregels: List<String> get() = logregels.filter { it.startsWith("beveiligingsevent ") }

    @BeforeEach
    fun begin() {
        logboek.list.clear()
        logboek.start()
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(logboek)
        client =
            WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:$poort")
                .responseTimeout(Duration.ofSeconds(30))
                .build()
        BffTestOmgeving.klok.reset()
        BffTestOmgeving.gebeurtenissen.clear()
        BffTestOmgeving.tenants.forEach { it.tokens.spec = TokenSpec() }
        leegProviderVerzoeken()
    }

    @AfterEach
    fun einde() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logboek)
        logboek.stop()
    }

    private fun leegProviderVerzoeken() {
        try {
            while (true) BffTestOmgeving.provider.takeRequest(10, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            // geen verzoeken meer
        }
    }

    class Browser(
        val tenant: TestTenant,
        hostOverride: String? = null,
    ) {
        val host: String = hostOverride ?: tenant.host
        val jar = linkedMapOf<String, String>()

        fun cookieHeader(): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

        fun slurp(headers: HttpHeaders) {
            headers[HttpHeaders.SET_COOKIE]?.forEach { ruw ->
                val cookie = HttpCookie.parse(ruw).first()
                if (cookie.maxAge == 0L) jar.remove(cookie.name) else jar[cookie.name] = cookie.value
            }
        }
    }

    /** Een request als de browser: de tenant bepaalt de `Host`; [headers] voegt `Origin`, CSRF-header enzovoort toe. */
    fun verstuur(
        browser: Browser,
        methode: String,
        pad: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        inhoudstype: String? = null,
    ): EntityExchangeResult<ByteArray> {
        var spec =
            client
                .method(
                    org.springframework.http.HttpMethod
                        .valueOf(methode),
                ).uri(URI.create("http://localhost:$poort$pad"))
                .header("Host", "${browser.host}:$poort")
        if (browser.jar.isNotEmpty()) spec = spec.header("Cookie", browser.cookieHeader())
        headers.forEach { (naam, waarde) -> spec = spec.header(naam, waarde) }
        inhoudstype?.let { spec = spec.header("Content-Type", it) }
        val resultaat = (if (body != null) spec.bodyValue(body) else spec).exchange().expectBody().returnResult()
        browser.slurp(resultaat.responseHeaders)
        return resultaat
    }

    fun get(
        browser: Browser,
        pad: String,
        headers: Map<String, String> = emptyMap(),
    ) = verstuur(browser, "GET", pad, headers)

    fun origin(browser: Browser) = "http://${browser.host}:$poort"

    /** Een muterend request zoals de SPA het stuurt: `Origin` van de tenant en het CSRF-token uit het cookie in de header. */
    fun post(
        browser: Browser,
        pad: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = "{}",
        inhoudstype: String? = "application/json",
    ): EntityExchangeResult<ByteArray> {
        val standaard = mutableMapOf("Origin" to origin(browser))
        browser.jar[BffNamen.CSRF_COOKIE]?.let { standaard["X-XSRF-TOKEN"] = it }
        return verstuur(browser, "POST", pad, standaard + headers, body, inhoudstype)
    }

    /** De IdP doet (niet-interactief) een redirect terug naar de redirect_uri met `code` en `state`. */
    fun idpAutoriseer(
        autorisatieUri: URI,
        gebruikersnaam: String = "test",
    ): URI {
        val idp = WebTestClient.bindToServer().baseUrl("${autorisatieUri.scheme}://${autorisatieUri.authority}").build()
        var resultaat =
            idp
                .get()
                .uri(autorisatieUri)
                .exchange()
                .expectBody()
                .returnResult()
        if (resultaat.status.value() == 200) {
            // prompt=login dwingt de testprovider tot zijn inlogformulier; de claims komen uit het tokenverzoek, niet uit het formulier
            resultaat =
                idp
                    .post()
                    .uri(autorisatieUri)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("username=${URLEncoder.encode(gebruikersnaam, Charsets.UTF_8)}&claims=%7B%7D")
                    .exchange()
                    .expectBody()
                    .returnResult()
        }
        check(resultaat.status.value() == 302) { "autorisatie gaf ${resultaat.status}" }
        return resultaat.responseHeaders.location!!
    }

    fun callback(
        browser: Browser,
        terugUri: URI,
        iss: String?,
    ): EntityExchangeResult<ByteArray> {
        val query = terugUri.rawQuery + (iss?.let { "&iss=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
        return get(browser, terugUri.rawPath + "?" + query)
    }

    class Inlog(
        val browser: Browser,
        val start: EntityExchangeResult<ByteArray>,
        val callback: EntityExchangeResult<ByteArray>,
    )

    /** Een hele login: start, de IdP en de callback. De IdP stuurt het ID-token dat [spec] beschrijft. */
    fun log(
        tenant: TestTenant,
        spec: TokenSpec = TokenSpec(),
        browser: Browser = Browser(tenant),
        startQuery: String = "inlogmiddel=digid",
        iss: String? = tenant.issuer,
    ): Inlog {
        tenant.tokens.spec = spec
        val start = get(browser, "${BffNamen.INLOGPAD}${tenant.oin}?$startQuery")
        check(start.status.value() == 302) { "login-start gaf ${start.status}" }
        val terug = idpAutoriseer(start.responseHeaders.location!!, spec.subject)
        return Inlog(browser, start, callback(browser, terug, iss))
    }

    fun slaagt(inlog: Inlog) {
        check(inlog.callback.status.value() == 200) { "callback gaf ${inlog.callback.status}" }
        check(BffNamen.SESSIE_COOKIE in inlog.browser.jar) { "geen sessiecookie" }
    }

    /** Het tokenverzoek dat het token-endpoint van deze tenant ontving. */
    fun tokenVerzoek(tenant: TestTenant): RecordedRequest {
        repeat(50) {
            val verzoek = BffTestOmgeving.provider.takeRequest(5, TimeUnit.SECONDS) ?: error("geen tokenverzoek voor ${tenant.naam}")
            if (verzoek.requestUrl?.encodedPath == "/${tenant.naam}/token") return verzoek
        }
        error("geen tokenverzoek voor ${tenant.naam}")
    }

    /** De client-assertion: handtekening met de sleutel van deze tenant, kid, alg, typ, iss, sub, aud en jti; en geen client_secret. */
    fun controleerAssertion(
        verzoek: RecordedRequest,
        tenant: TestTenant,
    ): SignedJWT {
        val formulier =
            verzoek.body.readUtf8().split("&").associate {
                it.substringBefore("=") to URLDecoder.decode(it.substringAfter("=", ""), Charsets.UTF_8)
            }
        check("client_secret" !in formulier) { "client_secret meegestuurd" }
        check(verzoek.getHeader("Authorization") == null) { "Authorization-header meegestuurd" }
        check(formulier["client_assertion_type"] == "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
        val jwt = SignedJWT.parse(formulier.getValue("client_assertion"))
        check(jwt.verify(RSASSAVerifier(tenant.sleutel.toPublicJWK()))) { "handtekening klopt niet met de sleutel van ${tenant.naam}" }
        check(jwt.header.algorithm == JWSAlgorithm.PS256)
        check(jwt.header.keyID == tenant.sleutel.keyID)
        check(jwt.jwtClaimsSet.issuer == tenant.clientId && jwt.jwtClaimsSet.subject == tenant.clientId)
        check(jwt.jwtClaimsSet.audience.single() == tenant.issuer) { "aud moet de issuer zijn" }
        check(jwt.header.type?.toString() == "client-authentication+jwt") { "typ: ${jwt.header.type}" }
        check(Base64.getUrlDecoder().decode(jwt.jwtClaimsSet.jwtid).size >= 32) { "jti korter dan 256 bit" }
        check(jwt.jwtClaimsSet.expirationTime != null)
        return jwt
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun eigenschappen(registry: DynamicPropertyRegistry) {
            registry.add("nl-portal.authentication.broker-url") { "http://localhost:${BffTestOmgeving.provider.baseUrl().port}" }
        }
    }
}