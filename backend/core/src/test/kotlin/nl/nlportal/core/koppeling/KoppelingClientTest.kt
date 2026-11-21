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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.util.ModuleNotConfiguredException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.web.reactive.function.client.WebClient
import reactor.util.context.Context
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Seam 2 van epic #499: de [KoppelingClient] tegen een nep-server, via het register en met een tenant in de Reactor Context. */
class KoppelingClientTest {
    private data class Zaak(
        val id: String = "",
    )

    private val bosrand = Oin("00000001234567890000")
    private val wijdeVeld = Oin("00000009876543210000")

    private lateinit var bron: MockWebServer
    private lateinit var andereBron: MockWebServer
    private lateinit var buitenstaander: MockWebServer

    @BeforeEach
    fun start() {
        bron = MockWebServer().apply { start() }
        andereBron = MockWebServer().apply { start() }
        buitenstaander = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        listOf(bron, andereBron, buitenstaander).forEach { runCatching { it.shutdown() } }
        boot.forEach { it.close() }
    }

    private fun koppeling(
        server: MockWebServer,
        pad: String = "",
        token: String = "geheim-token",
    ) = Koppeling().apply {
        url = server.url(pad).toString().trimEnd('/')
        auth.type = KoppelingAuthType.BEARER
        auth.token = token
    }

    private fun register(
        instellingen: KoppelingResilienceInstellingen = KoppelingResilienceInstellingen(),
        koppelingen: Map<Oin, Map<String, Koppeling>> = mapOf(bosrand to mapOf("zaken" to koppeling(bron))),
    ) = KoppelingRegister.bouw(koppelingen, bootWebClientBuilder(), instellingen)

    /** De builder zoals Spring Boot hem in productie levert (met zijn connector-instellingen), niet een kale. */
    private fun bootWebClientBuilder(): WebClient.Builder {
        val context =
            AnnotationConfigApplicationContext().apply {
                register(HttpClientAutoConfiguration::class.java, ReactiveHttpClientAutoConfiguration::class.java, WebClientAutoConfiguration::class.java)
                refresh()
            }
        boot += context
        return context.getBean(WebClient.Builder::class.java)
    }

    private val boot = mutableListOf<AnnotationConfigApplicationContext>()

    private fun <T> alsTenant(
        oin: Oin,
        blok: suspend () -> T,
    ): T = runBlocking(ReactorContext(Context.of(TenantContext.SLEUTEL, oin))) { blok() }

    private suspend fun <T> alsTenantSuspend(
        oin: Oin,
        blok: suspend () -> T,
    ): T = withContext(ReactorContext(Context.of(TenantContext.SLEUTEL, oin))) { blok() }

    private fun json(
        body: String,
        status: Int = 200,
    ) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun problem(
        body: String,
        status: Int,
    ) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/problem+json").setBody(body)

    private fun fout(blok: suspend () -> Any?): KoppelingException {
        val e = runCatching { alsTenant(bosrand, blok) }.exceptionOrNull()
        assertThat(e).isInstanceOf(KoppelingException::class.java)
        return e as KoppelingException
    }

    // Aanroepen en authenticatie

    @Test
    fun `een aanroep geeft het getypeerde antwoord en stuurt de authenticatie van de koppeling mee`() {
        bron.enqueue(json("""{"id":"z-1"}"""))

        val zaak = alsTenant(bosrand) { register().current("zaken").get<Zaak>("/zaken/z-1", query = mapOf("expand" to listOf("a b", "c&d"))) }

        assertThat(zaak).isEqualTo(Zaak("z-1"))
        val verzoek = bron.takeRequest()
        assertThat(verzoek.getHeader("Authorization")).isEqualTo("Bearer geheim-token")
        assertThat(verzoek.path).isEqualTo("/zaken/z-1?expand=a%20b&expand=c%26d")
    }

    @Test
    fun `muteren stuurt de body mee en geeft het antwoord terug, verwijderen geeft niets`() {
        bron.enqueue(json("""{"id":"nieuw"}""", 201))
        bron.enqueue(MockResponse().setResponseCode(204))
        val register = register()

        val gemaakt = alsTenant(bosrand) { register.current("zaken").post<Zaak, Zaak>("/zaken", Zaak("aanvraag")) }
        alsTenant(bosrand) { register.current("zaken").delete("/zaken/nieuw") }

        assertThat(gemaakt).isEqualTo(Zaak("nieuw"))
        assertThat(bron.takeRequest().body.readUtf8()).isEqualTo("""{"id":"aanvraag"}""")
        assertThat(bron.takeRequest().method).isEqualTo("DELETE")
    }

    @Test
    fun `een header die bij de koppeling hoort kan niet per aanroep worden gezet`() {
        assertThatThrownBy {
            alsTenant(bosrand) { register().current("zaken").get<Zaak>("/zaken/1", headers = mapOf("authorization" to "Bearer anders")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(bron.requestCount).isZero()
    }

    // Foutvorm RFC 9457

    @Test
    fun `een geldige problem json van de bron blijft behouden maar komt niet in het bericht`() {
        bron.enqueue(
            problem(
                """{"type":"https://bron.example/zaak-niet-gevonden","title":"Niet gevonden","status":404,"detail":"BSN 999993653 onbekend","instance":"/zaken/z-1"}""",
                404,
            ),
        )

        val e = fout { register().current("zaken").get<Zaak>("/zaken/z-1") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.BRON_PROBLEEM)
        assertThat(e.problem.status).isEqualTo(404)
        assertThat(e.problem.title).isEqualTo("Niet gevonden")
        assertThat(e.problem.detail).isEqualTo("BSN 999993653 onbekend")
        assertThat(e.problem.type.toString()).isEqualTo("https://bron.example/zaak-niet-gevonden")
        assertThat(e.message).doesNotContain("999993653", "Niet gevonden").contains(e.correlatieId, "zaken")
    }

    @Test
    fun `een 502 met problem json is een probleem van de bron`() {
        bron.enqueue(problem("""{"title":"Bad gateway","status":502}""", 502))

        val e = fout { register().current("zaken").get<Zaak>("/zaken/z-1") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.BRON_PROBLEEM)
        assertThat(e.status).isEqualTo(502)
    }

    @Test
    fun `een foutantwoord zonder geldige problem json wordt gesynthetiseerd, een 5xx als 502 en een 4xx met zijn eigen status`() {
        bron.enqueue(MockResponse().setResponseCode(500).setHeader("Content-Type", "text/html").setBody("<html>stacktrace met BSN 999993653</html>"))
        bron.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("weg"))
        bron.enqueue(MockResponse().setResponseCode(503))
        val register = register()

        val vijfhonderd = fout { register.current("zaken").get<Zaak>("/a") }
        val vierhonderdvier = fout { register.current("zaken").get<Zaak>("/b") }
        val leeg = fout { register.current("zaken").get<Zaak>("/c") }

        assertThat(vijfhonderd.soort).isEqualTo(KoppelingFoutsoort.BRON_NIET_CONFORM)
        assertThat(vijfhonderd.status).isEqualTo(502)
        assertThat(vijfhonderd.problem.type.toString()).isEqualTo("urn:nl-portal:koppeling:bron-niet-conform")
        assertThat(vijfhonderd.problem.detail).isNull()
        assertThat(vijfhonderd.message).doesNotContain("999993653", "stacktrace")
        assertThat(vierhonderdvier.status).isEqualTo(404)
        assertThat(leeg.status).isEqualTo(502)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"title":"x","status":"404"}""",
            """{"title":"x","status":200}""",
            """{"title":5,"status":404}""",
            """{"title":"x"}""",
            """["niet","een","object"]""",
            """geen json""",
        ],
    )
    fun `problem json die niet aan het formaat voldoet wordt behandeld als niet conform`(body: String) {
        bron.enqueue(problem(body, 404))

        val e = fout { register().current("zaken").get<Zaak>("/zaken/z-1") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.BRON_NIET_CONFORM)
        assertThat(e.problem.detail).isNull()
    }

    @Test
    fun `een netwerkfout geeft een gesynthetiseerde 502`() {
        bron.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val e = fout { register().current("zaken").get<Zaak>("/zaken/z-1") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.NETWERK)
        assertThat(e.status).isEqualTo(502)
        assertThat(e.problem.title).isNotBlank()
    }

    @Test
    fun `een bron die niet antwoordt geeft een timeout als 504`() {
        bron.enqueue(json("{}").setBodyDelay(3, TimeUnit.SECONDS))

        val e = fout { register(KoppelingResilienceInstellingen(timeout = Duration.ofMillis(200))).current("zaken").get<Zaak>("/zaken/z-1") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.TIMEOUT)
        assertThat(e.status).isEqualTo(504)
    }

    @Test
    fun `een succesvol antwoord dat niet past of leeg is geeft een onbruikbaar antwoord`() {
        bron.enqueue(json("""{"id":"""))
        bron.enqueue(MockResponse().setResponseCode(200))
        val register = register()

        val kapot = fout { register.current("zaken").get<Zaak>("/a") }
        val leeg = fout { register.current("zaken").get<Zaak>("/b") }

        assertThat(kapot.soort).isEqualTo(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR)
        assertThat(leeg.soort).isEqualTo(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR)
    }

    // Paginering

    @Test
    fun `een pagina bevat het aantal, de verwijzingen en de resultaten`() {
        bron.enqueue(json("""{"count":3,"next":"${bron.url("/zaken?page=2")}","previous":null,"results":[{"id":"1"},{"id":"2"}]}"""))

        val pagina = alsTenant(bosrand) { register().current("zaken").getPage<Zaak>("/zaken") }

        assertThat(pagina.count).isEqualTo(3)
        assertThat(pagina.results).containsExactly(Zaak("1"), Zaak("2"))
        assertThat(pagina.next).isEqualTo(bron.url("/zaken?page=2").toString())
        assertThat(pagina.previous).isNull()
    }

    @Test
    fun `alle paginas worden gevolgd en samengevoegd met het token van de koppeling`() {
        bron.enqueue(json("""{"count":3,"next":"${bron.url("/zaken?page=2")}","results":[{"id":"1"},{"id":"2"}]}"""))
        bron.enqueue(json("""{"count":3,"next":null,"results":[{"id":"3"}]}"""))

        val zaken = alsTenant(bosrand) { register().current("zaken").getAllPages<Zaak>("/zaken", query = mapOf("status" to listOf("open"))) }

        assertThat(zaken.map { it.id }).containsExactly("1", "2", "3")
        assertThat(bron.takeRequest().path).isEqualTo("/zaken?status=open")
        val tweede = bron.takeRequest()
        assertThat(tweede.path).isEqualTo("/zaken?page=2")
        assertThat(tweede.getHeader("Authorization")).isEqualTo("Bearer geheim-token")
    }

    @Test
    fun `een bron die eindeloos doorverwijst wordt afgebroken`() {
        repeat(3) { bron.enqueue(json("""{"count":9,"next":"${bron.url("/zaken")}","results":[]}""")) }

        val e = fout { register().current("zaken").getAllPages("/zaken", typeReferentie<ResultPage<Zaak>>(), maxPaginas = 3) }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR)
        assertThat(bron.requestCount).isEqualTo(3)
    }

    // Host-binding

    @Test
    fun `een volgende pagina op een andere host wordt geweigerd zonder aanroep en zonder credentials`() {
        bron.enqueue(json("""{"count":2,"next":"${buitenstaander.url("/zaken?page=2")}","results":[{"id":"1"}]}"""))

        val e = fout { register().current("zaken").getAllPages<Zaak>("/zaken") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.HOST_BINDING)
        assertThat(buitenstaander.requestCount).isZero()
        assertThat(bron.requestCount).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://localhost:PORT/zaken/api/x",
            "//evil.example/zaken/api/x",
            "http://evil.example/zaken/api/x",
            "http://localhost:1/zaken/api/x",
            "/zaken/api/../geheim",
            "/zaken/api/%2e%2e/geheim",
            "/zaken/api/%2E%2E/geheim",
            "BASE/zaken/apix/x",
            "BASE/andere/x",
            "..",
            "../x",
            "http://localhost:PORT@evil.example/zaken/api/x",
            "javascript:alert(1)",
            "/zaken/api/x y",
            "/zaken/api/x#fragment",
            "/zaken/api/..;/geheim",
            "/zaken/api/x;jsessionid=1",
            "/zaken/api/x%2F..%2F..%2Fgeheim",
            "/zaken/api/x%2f..%2fgeheim",
            "/zaken/api/x%5C..%5Cgeheim",
            "/zaken/api/%252e%252e/geheim",
            "/zaken/api/x%00",
        ],
    )
    fun `elke URL buiten schema, host, poort en padprefix van de basis wordt geweigerd zonder aanroep`(doel: String) {
        val koppelingen = mapOf(bosrand to mapOf("zaken" to koppeling(bron, "/zaken/api", "t")))

        val e = fout { register(koppelingen = koppelingen).current("zaken").get<Zaak>(doel.replace("PORT", bron.port.toString()).replace("BASE", bron.url("/").toString().trimEnd('/'))) }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.HOST_BINDING)
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een absolute URL onder de basis, ook met hoofdletters in de host, wordt gevolgd`() {
        bron.enqueue(json("""{"id":"ok"}"""))
        val koppelingen = mapOf(bosrand to mapOf("zaken" to koppeling(bron, "/zaken/api", "t")))
        val basis = bron.url("/zaken/api").toString().trimEnd('/')
        val absoluut = basis.replace("localhost", "LOCALHOST") + "/zaken/z-1"

        val zaak = alsTenant(bosrand) { register(koppelingen = koppelingen).current("zaken").get<Zaak>(absoluut) }

        assertThat(zaak.id).isEqualTo("ok")
        assertThat(bron.takeRequest().path).isEqualTo("/zaken/api/zaken/z-1")
    }

    @Test
    fun `een redirect naar een andere host wordt niet gevolgd`() {
        bron.enqueue(MockResponse().setResponseCode(302).setHeader("Location", buitenstaander.url("/geheim").toString()))

        val e = fout { register().current("zaken").get<Zaak>("/zaken/z-1") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.BRON_NIET_CONFORM)
        assertThat(buitenstaander.requestCount).isZero()
    }

    @Test
    fun `een redirect naar dezelfde host buiten de padprefix wordt niet gevolgd en laat het token niet mee`() {
        bron.enqueue(MockResponse().setResponseCode(307).setHeader("Location", bron.url("/beheer/geheim").toString()))
        bron.enqueue(json("""{"id":"niet-bedoeld"}"""))
        val koppelingen = mapOf(bosrand to mapOf("zaken" to koppeling(bron, "/zaken/api", "t")))

        val e = fout { register(koppelingen = koppelingen).current("zaken").get<Zaak>("/x") }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.BRON_NIET_CONFORM)
        assertThat(bron.requestCount).isEqualTo(1)
    }

    // Per tenant en koppeling

    @Test
    fun `elke tenant gebruikt zijn eigen bron en zijn eigen credentials`() {
        val register =
            register(
                koppelingen =
                    mapOf(
                        bosrand to mapOf("zaken" to koppeling(bron, token = "token-bosrand")),
                        wijdeVeld to mapOf("zaken" to koppeling(andereBron, token = "token-wijde-veld")),
                    ),
            )
        bron.enqueue(json("""{"id":"bosrand"}"""))
        andereBron.enqueue(json("""{"id":"wijde-veld"}"""))

        val a = alsTenant(bosrand) { register.current("zaken").get<Zaak>("/x") }
        val b = alsTenant(wijdeVeld) { register.current("zaken").get<Zaak>("/x") }

        assertThat(a.id).isEqualTo("bosrand")
        assertThat(b.id).isEqualTo("wijde-veld")
        assertThat(bron.takeRequest().getHeader("Authorization")).isEqualTo("Bearer token-bosrand")
        assertThat(andereBron.takeRequest().getHeader("Authorization")).isEqualTo("Bearer token-wijde-veld")
    }

    @Test
    fun `zonder tenant in de context is er geen koppeling, fail-closed`() {
        assertThatThrownBy { runBlocking { register().current("zaken") } }.isInstanceOf(TenantContextMissingException::class.java)
    }

    @Test
    fun `een tenant zonder koppelingen of een onbekende koppeling geeft een duidelijke fout`() {
        assertThatThrownBy { alsTenant(wijdeVeld) { register().current("zaken") } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThatThrownBy { alsTenant(bosrand) { register().current("zaken", "vergunningen") } }
            .isInstanceOf(ModuleNotConfiguredException::class.java)
            .hasMessageContaining("vergunningen")
    }

    @Test
    fun `een gekozen koppeling gaat voor de modulenaam`() {
        andereBron.enqueue(json("""{"id":"gekozen"}"""))
        val koppelingen = mapOf(bosrand to mapOf("zaken" to koppeling(bron), "vergunningen" to koppeling(andereBron)))

        val zaak = alsTenant(bosrand) { register(koppelingen = koppelingen).current("zaken", "vergunningen").get<Zaak>("/x") }

        assertThat(zaak.id).isEqualTo("gekozen")
        assertThat(bron.requestCount).isZero()
    }

    // Limiet

    @Test
    fun `boven de limiet op gelijktijdige aanroepen wordt direct geweigerd, de andere tenant merkt niets`() {
        bron.enqueue(json("""{"id":"traag"}""").setBodyDelay(1, TimeUnit.SECONDS))
        andereBron.enqueue(json("""{"id":"snel"}"""))
        val register =
            register(
                KoppelingResilienceInstellingen(maxGelijktijdig = 1, vensterGrootte = 2, minimumAanroepen = 2),
                mapOf(bosrand to mapOf("zaken" to koppeling(bron)), wijdeVeld to mapOf("zaken" to koppeling(andereBron))),
            )

        bron.enqueue(json("""{"id":"na-de-limiet"}"""))
        val resultaten =
            runBlocking {
                val traag =
                    async(ReactorContext(Context.of(TenantContext.SLEUTEL, bosrand)), start = CoroutineStart.UNDISPATCHED) {
                        runCatching { register.current("zaken").get<Zaak>("/x") }
                    }
                assertThat(bron.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
                val tweede = runCatching { alsTenantSuspend(bosrand) { register.current("zaken").get<Zaak>("/y") } }
                val derde = runCatching { alsTenantSuspend(bosrand) { register.current("zaken").get<Zaak>("/y") } }
                assertThat((derde.exceptionOrNull() as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.LIMIET)
                val buurman = alsTenantSuspend(wijdeVeld) { register.current("zaken").get<Zaak>("/x") }
                val eerste = traag.await()
                val daarna = alsTenantSuspend(bosrand) { register.current("zaken").get<Zaak>("/z") }
                Pair(Triple(eerste, tweede, buurman), daarna)
            }
        assertThat(resultaten.second.id).isEqualTo("na-de-limiet")

        val (eerste, tweede, buurman) = resultaten.first
        assertThat(eerste.getOrNull()).isEqualTo(Zaak("traag"))
        val geweigerd = tweede.exceptionOrNull() as KoppelingException
        assertThat(geweigerd.soort).isEqualTo(KoppelingFoutsoort.LIMIET)
        assertThat(geweigerd.status).isEqualTo(503)
        assertThat(buurman).isEqualTo(Zaak("snel"))
        assertThat(bron.requestCount).isEqualTo(2)
    }

    // Circuit breaker

    private val snelOpen = KoppelingResilienceInstellingen(vensterGrootte = 2, minimumAanroepen = 2, wachttijdOpen = Duration.ofMinutes(5))

    @Test
    fun `na herhaalde storingen staat de circuit breaker open, de andere tenant en koppeling blijven werken`() {
        repeat(2) { bron.enqueue(MockResponse().setResponseCode(500)) }
        andereBron.enqueue(json("""{"id":"gezond"}"""))
        val register =
            register(
                snelOpen,
                mapOf(bosrand to mapOf("zaken" to koppeling(bron)), wijdeVeld to mapOf("zaken" to koppeling(andereBron))),
            )

        repeat(2) { fout { register.current("zaken").get<Zaak>("/x") } }
        val open = fout { register.current("zaken").get<Zaak>("/x") }
        val buurman = alsTenant(wijdeVeld) { register.current("zaken").get<Zaak>("/x") }

        assertThat(open.soort).isEqualTo(KoppelingFoutsoort.CIRCUIT_OPEN)
        assertThat(open.status).isEqualTo(503)
        assertThat(bron.requestCount).isEqualTo(2)
        assertThat(buurman.id).isEqualTo("gezond")
    }

    @Test
    fun `een 4xx telt niet als storing en opent de circuit breaker niet`() {
        repeat(4) { bron.enqueue(problem("""{"title":"Niet gevonden","status":404}""", 404)) }
        bron.enqueue(json("""{"id":"nog-steeds-dicht"}"""))
        val register = register(snelOpen)

        repeat(4) { fout { register.current("zaken").get<Zaak>("/x") } }
        val zaak = alsTenant(bosrand) { register.current("zaken").get<Zaak>("/x") }

        assertThat(zaak.id).isEqualTo("nog-steeds-dicht")
    }

    @Test
    fun `een mengsel van 4xx en 5xx opent de circuit breaker alleen op de storingen`() {
        val register = register(KoppelingResilienceInstellingen(foutPercentage = 100f, vensterGrootte = 4, minimumAanroepen = 4, wachttijdOpen = Duration.ofMinutes(5)))
        repeat(4) {
            bron.enqueue(MockResponse().setResponseCode(500))
            bron.enqueue(problem("""{"title":"Niet gevonden","status":404}""", 404))
        }

        repeat(7) { fout { register.current("zaken").get<Zaak>("/x") } }
        val open = fout { register.current("zaken").get<Zaak>("/x") }

        assertThat(open.soort).isEqualTo(KoppelingFoutsoort.CIRCUIT_OPEN)
        assertThat(bron.requestCount).isEqualTo(7)
    }

    @Test
    fun `een tweede koppeling van dezelfde tenant blijft werken als de eerste open staat`() {
        repeat(2) { bron.enqueue(MockResponse().setResponseCode(500)) }
        andereBron.enqueue(json("""{"id":"gezond"}"""))
        val register = register(snelOpen, mapOf(bosrand to mapOf("zaken" to koppeling(bron), "vergunningen" to koppeling(andereBron))))

        repeat(3) { fout { register.current("zaken").get<Zaak>("/x") } }
        val zaak = alsTenant(bosrand) { register.current("vergunningen").get<Zaak>("/x") }

        assertThat(zaak.id).isEqualTo("gezond")
        assertThat(bron.requestCount).isEqualTo(2)
    }

    @Test
    fun `een timeout laat de limiet vrij`() {
        bron.enqueue(json("{}").setBodyDelay(3, TimeUnit.SECONDS))
        bron.enqueue(json("""{"id":"daarna"}"""))
        val register = register(KoppelingResilienceInstellingen(maxGelijktijdig = 1, timeout = Duration.ofMillis(300)))

        val timeout = fout { register.current("zaken").get<Zaak>("/x") }
        val daarna = alsTenant(bosrand) { register.current("zaken").get<Zaak>("/x") }

        assertThat(timeout.soort).isEqualTo(KoppelingFoutsoort.TIMEOUT)
        assertThat(daarna.id).isEqualTo("daarna")
    }

    @Test
    fun `een geannuleerde aanroep laat de limiet vrij`() {
        bron.enqueue(json("{}").setBodyDelay(5, TimeUnit.SECONDS))
        bron.enqueue(json("""{"id":"daarna"}"""))
        val register = register(KoppelingResilienceInstellingen(maxGelijktijdig = 1))

        val daarna =
            runBlocking {
                val traag =
                    async(ReactorContext(Context.of(TenantContext.SLEUTEL, bosrand)), start = CoroutineStart.UNDISPATCHED) {
                        register.current("zaken").get<Zaak>("/x")
                    }
                assertThat(bron.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
                traag.cancelAndJoin()
                alsTenantSuspend(bosrand) { register.current("zaken").get<Zaak>("/y") }
            }

        assertThat(daarna.id).isEqualTo("daarna")
    }

    @Test
    fun `een API-key-koppeling stuurt zijn sleutel in de eigen header en die kan niet per aanroep worden overschreven`() {
        bron.enqueue(json("""{"id":"ok"}"""))
        val apiKey =
            Koppeling().apply {
                url = bron.url("/").toString().trimEnd('/')
                auth.type = KoppelingAuthType.API_KEY
                auth.headerName = "X-Api-Key"
                auth.value = "sleutel"
            }
        val register = register(koppelingen = mapOf(bosrand to mapOf("zaken" to apiKey)))

        alsTenant(bosrand) { register.current("zaken").get<Zaak>("/x") }
        assertThat(bron.takeRequest().getHeader("X-Api-Key")).isEqualTo("sleutel")
        assertThatThrownBy { alsTenant(bosrand) { register.current("zaken").get<Zaak>("/x", headers = mapOf("x-api-key" to "anders")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(bron.requestCount).isEqualTo(1)
    }

    // Overige bewerkingen

    @Test
    fun `put en patch sturen de body en geven het antwoord terug, ook met de typereferentie zonder optionele argumenten`() {
        bron.enqueue(json("""{"id":"gewijzigd"}"""))
        bron.enqueue(json("""{"id":"deels"}"""))
        bron.enqueue(json("""{"id":"referentie"}"""))
        bron.enqueue(json("""{"id":"post-referentie"}"""))
        bron.enqueue(json("""{"count":1,"next":null,"results":[{"id":"p1"}]}"""))
        bron.enqueue(json("""{"id":"put-referentie"}"""))
        bron.enqueue(json("""{"id":"patch-referentie"}"""))
        bron.enqueue(MockResponse().setResponseCode(204))
        val register = register()

        val ids =
            alsTenant(bosrand) {
                val client = register.current("zaken")
                listOf(
                    client.put("/zaken/1", Zaak("nieuw"), typeReferentie<Zaak>()).id,
                    client.patch("/zaken/1", Zaak("deel"), typeReferentie<Zaak>()).id,
                    client.get("/zaken/1", typeReferentie<Zaak>()).id,
                    client.post("/zaken", Zaak("n"), typeReferentie<Zaak>()).id,
                    client
                        .getPage("/zaken", typeReferentie<ResultPage<Zaak>>())
                        .results
                        .single()
                        .id,
                    client.put("/zaken/1", Zaak("n"), typeReferentie<Zaak>()).id,
                    client.patch("/zaken/1", Zaak("n"), typeReferentie<Zaak>()).id,
                ).also { client.delete("/zaken/1") }
            }

        assertThat(ids).containsExactly("gewijzigd", "deels", "referentie", "post-referentie", "p1", "put-referentie", "patch-referentie")
        assertThat(bron.takeRequest().method).isEqualTo("PUT")
        assertThat(bron.takeRequest().method).isEqualTo("PATCH")
    }

    @Test
    fun `headers per aanroep gaan mee en een problem json zonder titel krijgt een standaardtitel`() {
        bron.enqueue(MockResponse().setResponseCode(204))
        bron.enqueue(json("""{"id":"ok"}"""))
        bron.enqueue(problem("""{"status":409}""", 409))
        val register = register()

        alsTenant(bosrand) { register.current("zaken").delete("/zaken/1", headers = mapOf("X-Reden" to "opruimen")) }
        alsTenant(bosrand) { register.current("zaken").get<Zaak>("/zaken/1", headers = mapOf("X-Test" to "1")) }
        val e = fout { register.current("zaken").get<Zaak>("/zaken/2") }

        assertThat(bron.takeRequest().getHeader("X-Reden")).isEqualTo("opruimen")
        assertThat(bron.takeRequest().getHeader("X-Test")).isEqualTo("1")
        assertThat(e.problem.title).isEqualTo("Onbekend probleem")
        assertThat(e.status).isEqualTo(409)
    }

    // Tenantgebondenheid

    @Test
    fun `een bewaarde client weigert een andere tenant dan waarvoor hij is verkregen`() {
        val register = register(koppelingen = mapOf(bosrand to mapOf("zaken" to koppeling(bron)), wijdeVeld to mapOf("zaken" to koppeling(andereBron))))
        val clientVanBosrand = alsTenant(bosrand) { register.current("zaken") }

        assertThatThrownBy { alsTenant(wijdeVeld) { clientVanBosrand.get<Zaak>("/x") } }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { runBlocking { clientVanBosrand.get<Zaak>("/x") } }.isInstanceOf(TenantContextMissingException::class.java)
        assertThat(bron.requestCount).isZero()
        assertThat(andereBron.requestCount).isZero()
    }

    // Register bij opstarten

    @Test
    fun `een koppeling met een ongeldige basis-URL laat het bouwen van het register falen, niet het eerste gebruik`() {
        val kapot = koppeling(bron).apply { url = "geen url" }

        assertThatThrownBy { register(koppelingen = mapOf(bosrand to mapOf("zaken" to kapot))) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `een onvolledig geconfigureerde koppeling bestaat niet in het register en geeft een duidelijke fout bij gebruik`() {
        val onvolledig = koppeling(bron).apply { auth.token = null }
        val register = register(koppelingen = mapOf(bosrand to mapOf("zaken" to onvolledig, "vergunningen" to koppeling(bron))))

        assertThatThrownBy { alsTenant(bosrand) { register.current("zaken") } }.isInstanceOf(ModuleNotConfiguredException::class.java)
        assertThat(alsTenant(bosrand) { register.current("vergunningen") }.naam).isEqualTo("vergunningen")
    }

    // Query

    @Test
    fun `een plusteken in een queryparameter wordt gecodeerd zodat het geen spatie wordt`() {
        bron.enqueue(json("""{"id":"ok"}"""))

        alsTenant(bosrand) { register().current("zaken").get<Zaak>("/x", query = mapOf("tel" to listOf("+31612345678"))) }

        assertThat(bron.takeRequest().path).isEqualTo("/x?tel=%2B31612345678")
    }
}