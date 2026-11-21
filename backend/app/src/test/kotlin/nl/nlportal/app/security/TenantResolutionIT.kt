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
package nl.nlportal.app.security

import nl.nlportal.app.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatusCode
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration

/**
 * De volledige app via HTTP met het tenantbestand van De Bosrand: tenantresolutie vóór de security-keten, kale 404
 * voor een onbekende host, en de actuator alleen op de managementpoort.
 */
@SpringBootTest(classes = [TestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class TenantResolutionIT(
    @LocalServerPort private val serverPort: Int,
    @LocalManagementPort private val managementPort: Int,
) {
    private val bosrand = "00000099000000000001"

    private fun client(port: Int = serverPort) = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

    private fun get(
        pad: String,
        host: String,
        vararg headers: Pair<String, String>,
    ) = client()
        .get()
        .uri(pad)
        .header("Host", host)
        .headers { h -> headers.forEach { h.add(it.first, it.second) } }
        .exchange()

    @Test
    fun `een bekende host bereikt de applicatie`() {
        for (host in listOf("bosrand.example", "BOSRAND.example:8443", "bosrand.example.", "localhost")) {
            get("/graphql", host).expectStatus().value { assertThat(it).describedAs(host).isNotEqualTo(404) }
        }
    }

    @Test
    fun `een onbekende host krijgt een kale 404 op elk pad, ook onder de security-keten`() {
        for (pad in listOf("/api/public/ping", "/graphql", "/oauth2/authorization/nl-portal", "/login/oauth2/code/nl-portal", "/logout", "/api/private")) {
            get(pad, "onbekend.example")
                .expectStatus()
                .isNotFound
                .expectBody()
                .isEmpty
        }
    }

    @Test
    fun `een gespoofte forwarded-header van een niet-vertrouwd adres telt niet`() {
        get("/graphql", "onbekend.example", "X-Forwarded-Host" to "bosrand.example").expectStatus().isNotFound
        get("/graphql", "onbekend.example", "Forwarded" to "host=bosrand.example").expectStatus().isNotFound
    }

    @Test
    fun `de actuator geeft 404 op de applicatiepoort en werkt op de managementpoort`() {
        get("/actuator/health", "bosrand.example").expectStatus().isNotFound
        client(managementPort)
            .get()
            .uri("/actuator/health")
            .header("Host", "onbekend.example")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `refresh en restart bestaan nergens`() {
        // In :app vraagt de security-keten eerst om inloggegevens (401); de 404 zonder security toetst TenancyOverHttpTest
        // in core, en de exposure uit de geleverde application.yml toetst ShippedConfigurationTest.
        for (poort in listOf(serverPort, managementPort)) {
            for (endpoint in listOf("refresh", "restart")) {
                client(poort).post().uri("/actuator/$endpoint").header("Host", "bosrand.example").exchange().expectStatus().value {
                    assertThat(it).describedAs("poort $poort $endpoint").isIn(401, 404)
                }
            }
        }
    }

    @Test
    fun `een IP-literal als host geeft 404`() {
        for (host in listOf("127.0.0.1", "127.0.0.1:$serverPort", "[::1]:$serverPort", "10.1.2.3")) {
            get("/api/public/tenant-probe", host)
                .expectStatus()
                .isNotFound
                .expectBody()
                .isEmpty
        }
    }

    @Test
    fun `de code achter de tenantresolutie leest de tenant van het request, ook na een dispatcherwissel`() {
        for (host in listOf("bosrand.example", "localhost", "BOSRAND.example.:8443")) {
            get("/api/public/tenant-probe", host)
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .isEqualTo(bosrand)
        }
    }

    @Test
    fun `een coroutine die de Reactor Context verliest, ziet geen standaardtenant`() {
        get("/api/public/tenant-verloren-probe", "bosrand.example")
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo("geen-tenant")
    }

    @Test
    fun `de OIN staat in de MDC van de logregels van een request, ook na een threadwissel`() {
        get("/api/public/mdc-probe", "bosrand.example")
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo(bosrand)
    }

    @Test
    fun `de OIN staat in de MDC van suspend-code na een dispatcherwissel`() {
        get("/api/public/mdc-suspend-probe", "bosrand.example")
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo(bosrand)
    }

    @Test
    fun `200 parallelle requests lekken niet en lezen altijd de tenant van hun eigen host`() {
        // De testconfiguratie heeft één tenant (stap 7 maakt er meer); de echte cross-tenant-lektest volgt in stap 8.
        // Dit toetst dat elk request altijd een tenant heeft, ook onder belasting, en dat een onbekende host nooit een tenant krijgt.
        val hosten = listOf("bosrand.example", "localhost", "onbekend.example", "BOSRAND.example:8443")
        val resultaten =
            Flux
                .range(0, 200)
                .flatMapSequential({ i ->
                    val host = hosten[i % hosten.size]
                    Mono
                        .fromCallable {
                            val antwoord = get("/api/public/tenant-probe", host).returnResult(String::class.java)
                            Triple(
                                host,
                                antwoord.status,
                                antwoord.responseBody
                                    .collectList()
                                    .block(Duration.ofSeconds(30))
                                    .orEmpty()
                                    .joinToString(""),
                            )
                        }.subscribeOn(Schedulers.boundedElastic())
                }, 50)
                .collectList()
                .block(Duration.ofSeconds(60))!!

        assertThat(resultaten).hasSize(200)
        resultaten.forEach { (host, status, body) ->
            if (host == "onbekend.example") {
                assertThat(status).describedAs(host).isEqualTo(HttpStatusCode.valueOf(404))
                assertThat(body).describedAs(host).isEmpty()
            } else {
                assertThat(status).describedAs(host).isEqualTo(HttpStatusCode.valueOf(200))
                assertThat(body).describedAs(host).isEqualTo(bosrand)
            }
        }
    }
}