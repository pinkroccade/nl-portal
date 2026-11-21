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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.test.scheduler.VirtualTimeScheduler
import reactor.util.context.Context
import java.time.Duration
import java.util.concurrent.TimeUnit

/** De bytes-stroom van de [KoppelingClient]: dezelfde garanties als een gewone aanroep, voor een groot antwoord zoals een document. */
class KoppelingClientStreamTest {
    private val bosrand = Oin("00000001234567890000")
    private val wijdeVeld = Oin("00000009876543210000")

    private lateinit var bron: MockWebServer
    private lateinit var buitenstaander: MockWebServer

    @BeforeEach
    fun start() {
        bron = MockWebServer().apply { start() }
        buitenstaander = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        listOf(bron, buitenstaander).forEach { runCatching { it.shutdown() } }
    }

    private fun koppeling(
        basis: String,
        pad: String = "",
    ) = Koppeling().apply {
        url = basis + pad
        auth.type = KoppelingAuthType.BEARER
        auth.token = "geheim-token"
    }

    private fun bronUrl() = bron.url("").toString().trimEnd('/')

    private fun register(
        instellingen: KoppelingResilienceInstellingen = KoppelingResilienceInstellingen(),
        koppelingen: Map<Oin, Map<String, Koppeling>> = mapOf(bosrand to mapOf("documenten" to koppeling(bronUrl()))),
    ) = KoppelingRegister.bouw(koppelingen, WebClient.builder(), instellingen)

    private fun <T> alsTenant(
        oin: Oin = bosrand,
        blok: suspend () -> T,
    ): T = runBlocking(ReactorContext(Context.of(TenantContext.SLEUTEL, oin))) { blok() }

    private suspend fun <T> alsTenantSuspend(
        oin: Oin,
        blok: suspend () -> T,
    ): T = withContext(ReactorContext(Context.of(TenantContext.SLEUTEL, oin))) { blok() }

    private suspend fun Flow<DataBuffer>.bytes(): ByteArray =
        toList().fold(ByteArray(0)) { totaal, buffer ->
            val deel = ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
            DataBufferUtils.release(buffer)
            totaal + deel
        }

    private fun fout(blok: suspend () -> Any?): KoppelingException {
        val e = runCatching { alsTenant(blok = blok) }.exceptionOrNull()
        assertThat(e).isInstanceOf(KoppelingException::class.java)
        return e as KoppelingException
    }

    private fun bytes(inhoud: String) = MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(inhoud)

    @Test
    fun `een stroom geeft de bytes van het antwoord en stuurt de authenticatie en accept van de koppeling mee`() {
        val inhoud = "x".repeat(300_000)
        bron.enqueue(bytes(inhoud))

        val ontvangen = alsTenant { register().current("documenten").getStream("/download", MediaType.APPLICATION_OCTET_STREAM).bytes() }

        assertThat(String(ontvangen)).isEqualTo(inhoud)
        val verzoek = bron.takeRequest()
        assertThat(verzoek.path).isEqualTo("/download")
        assertThat(verzoek.getHeader("Authorization")).isEqualTo("Bearer geheim-token")
        assertThat(verzoek.getHeader("Accept")).isEqualTo("application/octet-stream")
    }

    @Test
    fun `een stroom zonder opgegeven accept vraagt alles, ook als de koppeling JSON als standaard zou zetten`() {
        bron.enqueue(bytes("x"))

        alsTenant { register().current("documenten").getStream("/download").bytes() }

        assertThat(bron.takeRequest().getHeader("Accept")).isEqualTo("*/*")
    }

    @Test
    fun `een 4xx op een stroom blijft zichtbaar en lekt de body van de bron niet`() {
        bron.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("BSN 999990755 onbekend"))

        val e = fout { register().current("documenten").getStream("/download").bytes() }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.BRON_NIET_CONFORM)
        assertThat(e.status).isEqualTo(404)
        assertThat(e.message).doesNotContain("999990755")
    }

    @Test
    fun `een stroom naar een URL buiten de basis-URL gaat nergens heen`() {
        val e = fout { register().current("documenten").getStream(buitenstaander.url("/geheim").toString()).bytes() }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.HOST_BINDING)
        assertThat(buitenstaander.requestCount).isZero()
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een redirect op een stroom wordt niet gevolgd`() {
        bron.enqueue(MockResponse().setResponseCode(302).setHeader("Location", buitenstaander.url("/geheim").toString()))

        val e = fout { register().current("documenten").getStream("/download").bytes() }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.BRON_NIET_CONFORM)
        assertThat(buitenstaander.requestCount).isZero()
    }

    @Test
    fun `een header die bij de koppeling hoort kan niet per aanroep op een stroom worden gezet`() {
        assertThatThrownBy {
            alsTenant { register().current("documenten").getStream("/download", headers = mapOf("Authorization" to "Bearer anders")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een client van de ene tenant geeft geen stroom voor een andere tenant`() {
        val client = alsTenant { register().current("documenten") }

        assertThatThrownBy { alsTenant(wijdeVeld) { client.getStream("/download") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een stroom die stokt geeft een timeout als 504`() {
        bron.enqueue(bytes("traag").setBodyDelay(3, TimeUnit.SECONDS))

        val e = fout { register(KoppelingResilienceInstellingen(timeout = Duration.ofMillis(300))).current("documenten").getStream("/download").bytes() }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.TIMEOUT)
        assertThat(e.status).isEqualTo(504)
    }

    @Test
    fun `een stroom die halverwege afbreekt geeft een netwerkfout`() {
        bron.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("x".repeat(100_000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val e = fout { register().current("documenten").getStream("/download").bytes() }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.NETWERK)
    }

    private fun grootAntwoord() = bytes("x".repeat(6_000_000))

    /** Start een afnemer die na het eerste brok stilvalt, zodat de stroom open blijft staan; de [Job] annuleert hem. */
    private fun kotlinx.coroutines.CoroutineScope.stilgezet(
        register: KoppelingRegister,
        pauze: Long = 20_000,
        eersteBrok: CompletableDeferred<Unit>? = null,
    ) = async(ReactorContext(Context.of(TenantContext.SLEUTEL, bosrand)), start = CoroutineStart.UNDISPATCHED) {
        runCatching {
            register.current("documenten").getStream("/groot").collect { buffer ->
                DataBufferUtils.release(buffer)
                eersteBrok?.complete(Unit)
                delay(pauze)
            }
        }
    }

    @Test
    fun `stromen hebben een eigen limiet en stilgezette downloads blokkeren metadata-aanroepen niet`() {
        val stromen = 3
        repeat(stromen) { bron.enqueue(grootAntwoord()) }
        bron.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        val register = register(KoppelingResilienceInstellingen(maxGelijktijdig = 1, maxGelijktijdigeStromen = stromen, timeout = Duration.ofSeconds(30)))

        val (geweigerd, metadata) =
            runBlocking {
                val afnemers = (1..stromen).map { stilgezet(register) }
                repeat(stromen) { assertThat(bron.takeRequest(5, TimeUnit.SECONDS)).isNotNull() }
                val geweigerd = runCatching { alsTenantSuspend(bosrand) { register.current("documenten").getStream("/groot").bytes() } }
                val metadata = runCatching { alsTenantSuspend(bosrand) { register.current("documenten").get<Map<String, String>>("/metadata") } }
                afnemers.forEach { it.cancel() }
                geweigerd to metadata
            }

        assertThat((geweigerd.exceptionOrNull() as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.LIMIET)
        assertThat(metadata.getOrNull()).isEmpty()
    }

    @Test
    fun `een trage lezer die de stroom laat stokken opent de circuit breaker niet en geeft zijn plek vrij`() {
        bron.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        repeat(4) { bron.enqueue(grootAntwoord()) }
        bron.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        val register =
            register(
                KoppelingResilienceInstellingen(maxGelijktijdigeStromen = 1, vensterGrootte = 2, minimumAanroepen = 2, timeout = Duration.ofMillis(600)),
            )
        // Virtuele tijd: de timeouts van Reactor lopen op deze scheduler en verstrijken alleen als de test de klok vooruitzet.
        val klok = VirtualTimeScheduler.getOrSet()
        try {
            // Een eerste aanroep warmt de verbinding op.
            alsTenant { register.current("documenten").get<Map<String, String>>("/warm") }
            bron.takeRequest()

            runBlocking {
                repeat(4) {
                    val eersteBrok = CompletableDeferred<Unit>()
                    val trage = stilgezet(register, eersteBrok = eersteBrok)
                    assertThat(bron.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
                    // De lezer heeft het eerste brok en staat stil; de timeout van de eerste respons is daarmee voorbij.
                    withTimeout(10_000) { eersteBrok.await() }
                    // Ruim langer dan de timeout: de stroom wordt door de stiltetimeout afgebroken terwijl de lezer nog stilstaat.
                    klok.advanceTimeBy(Duration.ofMillis(1_800))
                    trage.cancel()
                }
            }
            val metadata = alsTenant { register.current("documenten").get<Map<String, String>>("/metadata") }

            assertThat(metadata).isEmpty()
        } finally {
            VirtualTimeScheduler.reset()
        }
    }

    @Test
    fun `annuleren van een stroom geeft de limiet en de circuit breaker vrij`() {
        repeat(3) { bron.enqueue(grootAntwoord()) }
        bron.enqueue(bytes("daarna"))
        val register = register(KoppelingResilienceInstellingen(maxGelijktijdigeStromen = 1, vensterGrootte = 1, minimumAanroepen = 1, foutPercentage = 1f))

        repeat(3) {
            alsTenant {
                register
                    .current("documenten")
                    .getStream("/groot")
                    .first()
                    .let { DataBufferUtils.release(it) }
            }
        }
        val daarna = alsTenant { register.current("documenten").getStream("/c").bytes() }

        assertThat(String(daarna)).isEqualTo("daarna")
    }

    @Test
    fun `annuleren vóór de eerste respons geeft de limiet vrij en telt niet als storing van de bron`() {
        bron.enqueue(bytes("traag").setHeadersDelay(2, TimeUnit.SECONDS))
        bron.enqueue(bytes("daarna"))
        val register = register(KoppelingResilienceInstellingen(maxGelijktijdigeStromen = 1, vensterGrootte = 1, minimumAanroepen = 1, foutPercentage = 1f))

        runBlocking {
            val afnemer = async(Dispatchers.Default + ReactorContext(Context.of(TenantContext.SLEUTEL, bosrand))) { register.current("documenten").getStream("/traag").bytes() }
            assertThat(bron.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
            afnemer.cancel()
            afnemer.join()
        }
        val daarna = alsTenant { register.current("documenten").getStream("/c").bytes() }

        assertThat(String(daarna)).isEqualTo("daarna")
    }

    @Test
    fun `een foutstatus bij de eerste respons telt wel als storing van de bron`() {
        repeat(2) { bron.enqueue(MockResponse().setResponseCode(500)) }
        val register = register(KoppelingResilienceInstellingen(vensterGrootte = 2, minimumAanroepen = 2))

        repeat(2) { fout { register.current("documenten").getStream("/a").bytes() } }
        val derde = fout { register.current("documenten").getStream("/a").bytes() }

        assertThat(derde.soort).isEqualTo(KoppelingFoutsoort.CIRCUIT_OPEN)
    }

    @Test
    fun `een bron die niet antwoordt binnen de tijd geeft een timeout vóór de eerste respons`() {
        bron.enqueue(bytes("laat").setHeadersDelay(3, TimeUnit.SECONDS))

        val e = fout { register(KoppelingResilienceInstellingen(timeout = Duration.ofMillis(300))).current("documenten").getStream("/a").bytes() }

        assertThat(e.soort).isEqualTo(KoppelingFoutsoort.TIMEOUT)
    }

    // beheertUrl: het aanwijzen van de koppeling bij een URL die een bron teruggaf

    private fun beheert(
        basis: String,
        url: String,
    ): Boolean {
        val client = alsTenant { register(koppelingen = mapOf(bosrand to mapOf("d" to koppeling(basis)))).current("d") }
        return client.beheertUrl(url)
    }

    @Test
    fun `een URL onder de basis-URL hoort bij de koppeling`() {
        assertThat(beheert("https://open-zaak.example/documenten/api/v1", "https://open-zaak.example/documenten/api/v1/enkelvoudiginformatieobjecten/1")).isTrue()
        assertThat(beheert("https://open-zaak.example/documenten/api/v1/", "https://OPEN-ZAAK.example:443/documenten/api/v1/x")).isTrue()
    }

    @Test
    fun `een URL die de basis-URL alleen bevat hoort er niet bij`() {
        val basis = "https://open-zaak.example/documenten/api/v1"
        assertThat(beheert(basis, "https://kwaad.example/x?doel=$basis/enkelvoudiginformatieobjecten/1")).isFalse()
        assertThat(beheert(basis, "https://kwaad.example/$basis/x")).isFalse()
        assertThat(beheert(basis, "https://open-zaak.example.kwaad.example/documenten/api/v1/x")).isFalse()
        assertThat(beheert(basis, "https://user@open-zaak.example/documenten/api/v1/x")).isFalse()
        assertThat(beheert(basis, "https://open-zaak.example/documenten/api/v10/x")).isFalse()
        assertThat(beheert(basis, "https://open-zaak.example/documenten/api/v1/../v2/x")).isFalse()
        assertThat(beheert(basis, "http://open-zaak.example/documenten/api/v1/x")).isFalse()
        assertThat(beheert(basis, "https://open-zaak.example:8443/documenten/api/v1/x")).isFalse()
        assertThat(beheert(basis, "not a url")).isFalse()
    }
}