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
package nl.nlportal.core.tenancy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Seam: een request gaat door de tenantresolutie-`WebFilter`; de code erachter leest de tenant alleen via
 * [TenantContext]. Toetst de hostregels uit "Proxy en host" van de spec.
 */
class TenantWebFilterTest {
    private val bosrand = Oin("00000099000000000001")
    private val wijdeVeld = Oin("00000099000000000002")
    private val ingress = InetSocketAddress(InetAddress.getByName("10.1.2.3"), 4000)
    private val buitenstaander = InetSocketAddress(InetAddress.getByName("203.0.113.9"), 4000)

    private fun tenant(
        oin: Oin,
        vararg domeinen: String,
    ) = TenantDefinition(
        oin = oin,
        naam = "Gemeente $oin",
        omgevingstype = Omgevingstype.TEST,
        domeinen = domeinen.toList(),
        thema = Thema(titel = "t"),
        inlogmiddelen = setOf(Inlogmiddel.GEBRUIKERSNAAM),
        authenticatie = Authenticatie("https://login.example/realms/${oin.value}", "/k.jwk"),
        minimumNiveau = Betrouwbaarheidsniveau.SUBSTANTIEEL,
        sessie = Sessie(),
    )

    private val directory =
        TenantDirectory(
            listOf(tenant(bosrand, "bosrand.example", "www.bosrand.example"), tenant(wijdeVeld, "wijdeveld.example")),
        )

    private val aangeroepen = AtomicInteger()

    private val filter = TenantWebFilter(directory, TrustedProxies(listOf("10.0.0.0/8")))

    /** De code achter het filter: antwoordt met de tenant uit de [TenantContext], gelezen in `suspend`-code. */
    private val chain =
        WebFilterChain { exchange ->
            aangeroepen.incrementAndGet()
            mono {
                val oin = TenantContext.current()
                exchange.response.statusCode = HttpStatus.OK
                exchange.response.headers.set("X-Test-Tenant", oin.value)
            }.then()
        }

    private fun aanroep(
        host: String?,
        van: InetSocketAddress? = buitenstaander,
        pad: String = "/graphql",
        headers: Map<String, String> = emptyMap(),
    ): MockServerWebExchange {
        val builder = MockServerHttpRequest.get(pad)
        host?.let { builder.header("Host", it) }
        van?.let { builder.remoteAddress(it) }
        headers.forEach { (naam, waarde) -> builder.header(naam, waarde) }
        return MockServerWebExchange.from(builder).also { filter.filter(it, chain).block() }
    }

    private fun MockServerWebExchange.tenant() = response.headers.getFirst("X-Test-Tenant")

    private fun MockServerWebExchange.isKaleNotFound() {
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.headerNames()).isEmpty()
        assertThat(response.bodyAsString.block()).isNullOrEmpty()
    }

    @Test
    fun `een bekende host levert zijn tenant in de context`() {
        assertThat(aanroep("bosrand.example").tenant()).isEqualTo(bosrand.value)
        assertThat(aanroep("www.bosrand.example").tenant()).isEqualTo(bosrand.value)
        assertThat(aanroep("wijdeveld.example").tenant()).isEqualTo(wijdeVeld.value)
    }

    @Test
    fun `een onbekende host krijgt een kale 404, ook op oauth2 login en logout`() {
        for (pad in listOf("/graphql", "/oauth2/authorization/nl-portal", "/login/oauth2/code/nl-portal", "/logout")) {
            aanroep("onbekend.example", pad = pad).isKaleNotFound()
        }
        assertThat(aangeroepen).hasValue(0)
    }

    @Test
    fun `een host wordt genormaliseerd voor het opzoeken`() {
        assertThat(aanroep("BosRand.Example").tenant()).describedAs("hoofdletters").isEqualTo(bosrand.value)
        assertThat(aanroep("bosrand.example:8443").tenant()).describedAs("poort").isEqualTo(bosrand.value)
        assertThat(aanroep("bosrand.example.").tenant()).describedAs("trailing dot").isEqualTo(bosrand.value)
        assertThat(aanroep("BOSRAND.example.:443").tenant()).describedAs("alles samen").isEqualTo(bosrand.value)
    }

    @Test
    fun `een host die niet precies een toegestaan domein is, krijgt 404`() {
        val slecht =
            listOf(
                "",
                "bosrand.example..",
                "xbosrand.example",
                "bosrand.example.evil.example",
                "evil.example#@bosrand.example",
                "bosrand.example@evil.example",
                "bosrand.example:abc",
                "bosrand.example:",
                "127.0.0.1",
                "[::1]",
                "[::1]:8080",
                "bosrand.example, evil.example",
                " bosrand.example",
            )
        slecht.forEach { aanroep(it).also { exchange -> exchange.isKaleNotFound() } }
    }

    @Test
    fun `een request zonder Host-header of met twee Host-headers krijgt 404`() {
        aanroep(host = null).isKaleNotFound()
        aanroep("bosrand.example", headers = mapOf("Host" to "wijdeveld.example")).also {
            // twee Host-headers: onduidelijk welke de tenant is
            it.isKaleNotFound()
        }
    }

    @Test
    fun `zonder Host-header telt de request-URI alleen bij een in-memory request zonder afzenderadres`() {
        fun zonderHost(van: InetSocketAddress?) =
            MockServerWebExchange
                .from(MockServerHttpRequest.get("http://bosrand.example/x").also { builder -> van?.let { builder.remoteAddress(it) } })
                .also { filter.filter(it, chain).block() }

        assertThat(zonderHost(null).tenant()).isEqualTo(bosrand.value)
        zonderHost(buitenstaander).isKaleNotFound()
        zonderHost(ingress).isKaleNotFound()
    }

    @Test
    fun `gespoofte forwarded-headers van een niet-vertrouwd adres tellen niet`() {
        aanroep("onbekend.example", headers = mapOf("X-Forwarded-Host" to "bosrand.example")).isKaleNotFound()
        aanroep("onbekend.example", headers = mapOf("Forwarded" to "host=bosrand.example")).isKaleNotFound()
        assertThat(aanroep("wijdeveld.example", headers = mapOf("X-Forwarded-Host" to "bosrand.example")).tenant())
            .isEqualTo(wijdeVeld.value)
        assertThat(aanroep("wijdeveld.example", van = null, headers = mapOf("X-Forwarded-Host" to "bosrand.example")).tenant())
            .describedAs("onbekend afzenderadres is niet vertrouwd")
            .isEqualTo(wijdeVeld.value)
    }

    @Test
    fun `forwarded-headers van een vertrouwde proxy bepalen de host`() {
        assertThat(aanroep("intern.svc", van = ingress, headers = mapOf("X-Forwarded-Host" to "Bosrand.Example:443")).tenant())
            .isEqualTo(bosrand.value)
        assertThat(aanroep("intern.svc", van = ingress, headers = mapOf("Forwarded" to "for=1.2.3.4;host=wijdeveld.example;proto=https")).tenant())
            .isEqualTo(wijdeVeld.value)
        assertThat(aanroep("bosrand.example", van = ingress).tenant()).describedAs("zonder forwarded-header telt Host").isEqualTo(bosrand.value)
    }

    @Test
    fun `bij meerdere forwarded-waarden telt de laatste, die de vertrouwde proxy toevoegde`() {
        assertThat(
            aanroep("intern.svc", van = ingress, headers = mapOf("X-Forwarded-Host" to "bosrand.example, wijdeveld.example")).tenant(),
        ).isEqualTo(wijdeVeld.value)
        assertThat(
            aanroep("intern.svc", van = ingress, headers = mapOf("Forwarded" to "host=bosrand.example, host=wijdeveld.example")).tenant(),
        ).isEqualTo(wijdeVeld.value)
        aanroep("intern.svc", van = ingress, headers = mapOf("X-Forwarded-Host" to "wijdeveld.example, onbekend.example")).isKaleNotFound()
    }

    @Test
    fun `een onleesbare forwarded-header van een vertrouwde proxy valt niet terug op Host`() {
        aanroep("bosrand.example", van = ingress, headers = mapOf("X-Forwarded-Host" to "")).isKaleNotFound()
        aanroep("bosrand.example", van = ingress, headers = mapOf("Forwarded" to "for=1.2.3.4")).isKaleNotFound()
        aanroep("intern.svc", van = ingress, headers = mapOf("Forwarded" to "for=1.2.3.4", "X-Forwarded-Host" to "bosrand.example")).isKaleNotFound()
        aanroep("bosrand.example", van = ingress, headers = mapOf("Forwarded" to "host=\"[::1\"")).isKaleNotFound()
    }

    @Test
    fun `Forwarded en X-Forwarded-Host die elkaar tegenspreken geven 404, ook van een vertrouwde proxy`() {
        // De ingress overschrijft X-Forwarded-Host maar laat een door de client gestuurde Forwarded soms staan.
        aanroep("intern.svc", van = ingress, headers = mapOf("Forwarded" to "host=wijdeveld.example", "X-Forwarded-Host" to "bosrand.example")).isKaleNotFound()
        assertThat(aanroep("intern.svc", van = ingress, headers = mapOf("Forwarded" to "host=Bosrand.example:443", "X-Forwarded-Host" to "bosrand.example")).tenant())
            .describedAs("zelfde host in beide headers")
            .isEqualTo(bosrand.value)
    }

    @Test
    fun `zonder tenantcontext faalt lezen in plaats van een standaardtenant te geven`() {
        assertThatThrownBy { runBlocking { TenantContext.current() } }.isInstanceOf(TenantContextMissingException::class.java)
        assertThatThrownBy { TenantContext.currentMono().block() }.isInstanceOf(TenantContextMissingException::class.java)
    }

    @Test
    fun `de tenant blijft zichtbaar na een dispatcherwissel en in geneste lezers`() {
        val ketting =
            WebFilterChain { exchange ->
                mono {
                    val buiten = TenantContext.current()
                    val binnen =
                        withContext(Dispatchers.Default) {
                            delay(5)
                            withContext(Dispatchers.IO) { TenantContext.current() }
                        }
                    val gemengd = TenantContext.currentMono().awaitSingle()
                    exchange.response.headers.set("X-Test-Tenant", listOf(buiten, binnen, gemengd).distinct().joinToString())
                }.then()
            }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header("Host", "wijdeveld.example"))
        filter.filter(exchange, ketting).block()
        assertThat(exchange.tenant()).isEqualTo(wijdeVeld.value)
    }

    @Test
    fun `200 parallelle requests over twee tenants zien nooit elkaars tenant`() {
        val fouten = AtomicInteger()
        val hosten = listOf("bosrand.example" to bosrand, "wijdeveld.example" to wijdeVeld)
        Flux
            .range(0, 200)
            .flatMapSequential({ i ->
                val (host, verwacht) = hosten[i % 2]
                val ketting =
                    WebFilterChain { exchange ->
                        mono {
                            delay((i % 7).toLong())
                            val gelezen = withContext(Dispatchers.IO) { TenantContext.current() }
                            if (gelezen != verwacht) fouten.incrementAndGet()
                            exchange.response.headers.set("X-Test-Tenant", gelezen.value)
                        }.then()
                    }
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header("Host", host))
                filter.filter(exchange, ketting).thenReturn(exchange)
            }, 200)
            .collectList()
            .block(Duration.ofSeconds(30))
            .orEmpty()
            .also { antwoorden ->
                assertThat(antwoorden).hasSize(200)
                antwoorden.forEachIndexed { i, exchange -> assertThat(exchange.tenant()).isEqualTo(hosten[i % 2].second.value) }
            }
        assertThat(fouten).hasValue(0)
    }

    @Test
    fun `de filtervolgorde ligt voor Spring Security`() {
        // WebFilterChainProxy draait op -100; de tenant moet er al zijn en een onbekende host mag er nooit komen.
        assertThat(filter.order).isLessThan(-100)
    }

    @Test
    fun `een lege lijst vertrouwde proxies vertrouwt niemand`() {
        val zonder = TenantWebFilter(directory, TrustedProxies(emptyList()))
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest
                    .get("/x")
                    .header("Host", "intern.svc")
                    .header("X-Forwarded-Host", "bosrand.example")
                    .remoteAddress(ingress),
            )
        zonder.filter(exchange, chain).block()
        exchange.isKaleNotFound()
    }
}