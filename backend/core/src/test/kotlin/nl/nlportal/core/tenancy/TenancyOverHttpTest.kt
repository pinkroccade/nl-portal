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

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Seam: de app via HTTP, met een echte tenantmap, een echte server en een echte managementpoort.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "nl-portal.tenancy.directory=src/test/resources/tenancy/bosrand",
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health",
        "management.endpoints.access.default=read_only",
        "management.metrics.distribution.percentiles-histogram.http.server.requests=false",
    ],
)
@Import(TenancyOverHttpTest.Probe::class)
class TenancyOverHttpTest(
    @LocalServerPort private val serverPort: Int,
    @LocalManagementPort private val managementPort: Int,
    @Autowired private val meterRegistry: MeterRegistry,
) {
    @TestConfiguration
    class Probe {
        @RestController
        class ProbeController {
            @GetMapping("/tenant-probe")
            suspend fun tenant() = TenantContext.current().value

            @GetMapping("/mdc-probe")
            fun mdc(): Mono<String> =
                Mono
                    .just("x")
                    .publishOn(Schedulers.boundedElastic())
                    .map { MDC.get("oin") ?: "geen" }
        }
    }

    private fun client(port: Int) = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

    private fun app(host: String) =
        client(serverPort)
            .get()
            .uri("/tenant-probe")
            .header("Host", host)
            .exchange()

    @Test
    fun `een bekende host krijgt zijn tenant, een onbekende host een kale 404`() {
        app("bosrand.example:8443")
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo("00000099000000000001")
        app("onbekend.example")
            .expectStatus()
            .isNotFound
            .expectBody()
            .isEmpty
    }

    @Test
    fun `een onbekende host krijgt een kale 404 ook op oauth2-paden`() {
        client(serverPort)
            .get()
            .uri("/oauth2/authorization/nl-portal")
            .header("Host", "onbekend.example")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
            .isEmpty
    }

    @Test
    fun `de actuator geeft 404 op de applicatiepoort, ook voor een bekende host`() {
        client(serverPort)
            .get()
            .uri("/actuator/health")
            .header("Host", "bosrand.example")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `de managementpoort heeft geen tenantresolutie`() {
        client(managementPort)
            .get()
            .uri("/actuator/health")
            .header("Host", "onbekend.example")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `refresh en restart staan niet in de exposure`() {
        client(managementPort)
            .get()
            .uri("/actuator/refresh")
            .exchange()
            .expectStatus()
            .isNotFound
        client(managementPort)
            .post()
            .uri("/actuator/restart")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `de OIN staat in de MDC, ook na een threadwissel`() {
        client(serverPort)
            .get()
            .uri("/mdc-probe")
            .header("Host", "bosrand.example")
            .exchange()
            .expectBody(String::class.java)
            .isEqualTo("00000099000000000001")
    }

    @Test
    fun `de HTTP-metrics dragen de OIN als label`() {
        app("bosrand.example").expectStatus().isOk
        app("onbekend.example").expectStatus().isNotFound

        val timers = meterRegistry.find("http.server.requests").timers()
        assertThat(timers.mapNotNull { it.id.getTag("oin") }).contains("00000099000000000001", "none")
    }
}