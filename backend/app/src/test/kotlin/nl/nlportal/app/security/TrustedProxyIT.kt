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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient

/** De app via HTTP met de client als vertrouwde proxy (de ingress): `Forwarded` en `X-Forwarded-Host` bepalen de host. */
@SpringBootTest(
    classes = [TestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["nl-portal.tenancy.trusted-proxies=127.0.0.1,::1"],
)
@Tag("integration")
class TrustedProxyIT(
    @LocalServerPort private val serverPort: Int,
) {
    private val bosrand = "00000099000000000001"

    private fun get(
        host: String,
        vararg headers: Pair<String, String>,
    ) = WebTestClient
        .bindToServer()
        .baseUrl("http://localhost:$serverPort")
        .build()
        .get()
        .uri("/api/public/tenant-probe")
        .header("Host", host)
        .headers { h -> headers.forEach { h.add(it.first, it.second) } }
        .exchange()

    @Test
    fun `van een vertrouwde proxy bepaalt X-Forwarded-Host de tenant`() {
        get("intern.svc", "X-Forwarded-Host" to "Bosrand.Example:443")
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo(bosrand)
        get("bosrand.example", "X-Forwarded-Host" to "onbekend.example")
            .expectStatus()
            .isNotFound
            .expectBody()
            .isEmpty
    }

    @Test
    fun `van een vertrouwde proxy bepaalt Forwarded host de tenant`() {
        get("intern.svc", "Forwarded" to "for=1.2.3.4;host=bosrand.example;proto=https")
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo(bosrand)
    }

    @Test
    fun `Forwarded en X-Forwarded-Host die elkaar tegenspreken geven 404 en een Forwarded zonder host ook`() {
        get("intern.svc", "Forwarded" to "host=onbekend.example", "X-Forwarded-Host" to "bosrand.example").expectStatus().isNotFound
        get("bosrand.example", "Forwarded" to "for=1.2.3.4").expectStatus().isNotFound
    }

    @Test
    fun `een IP-literal als host geeft 404, ook van een vertrouwde proxy`() {
        get("127.0.0.1:$serverPort").expectStatus().isNotFound
        get("[::1]:$serverPort").expectStatus().isNotFound
        get("intern.svc", "X-Forwarded-Host" to "10.1.2.3").expectStatus().isNotFound
    }
}