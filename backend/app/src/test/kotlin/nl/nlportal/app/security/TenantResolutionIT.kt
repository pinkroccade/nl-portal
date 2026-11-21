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
        for (poort in listOf(serverPort, managementPort)) {
            for (endpoint in listOf("refresh", "restart")) {
                // Op de applicatiepoort bestaat de actuator niet (404); op de managementpoort vraagt alles buiten health om inloggegevens.
                client(poort).post().uri("/actuator/$endpoint").header("Host", "bosrand.example").exchange().expectStatus().value {
                    assertThat(it).describedAs("poort $poort").isIn(401, 404)
                }
            }
        }
    }

    @Test
    fun `200 parallelle requests over een bekende en een onbekende host lekken niet`() {
        val resultaten =
            Flux
                .range(0, 200)
                .flatMapSequential({ i ->
                    val bekend = i % 2 == 0
                    Mono
                        .fromCallable {
                            bekend to get("/graphql", if (bekend) "bosrand.example" else "onbekend.example").returnResult(String::class.java).status
                        }.subscribeOn(
                            reactor.core.scheduler.Schedulers
                                .boundedElastic(),
                        )
                }, 50)
                .collectList()
                .block(Duration.ofSeconds(60))!!

        assertThat(resultaten).hasSize(200)
        assertThat(resultaten.filter { it.first }.map { it.second }).doesNotContain(HttpStatusCode.valueOf(404))
        assertThat(resultaten.filterNot { it.first }.map { it.second }).containsOnly(HttpStatusCode.valueOf(404))
    }
}