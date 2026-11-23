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
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.Socket

/**
 * De app via HTTP zoals op Kubernetes: cloudplatform `kubernetes` (waar Spring Boot zelf forwarded-headers aanzet als
 * de instelling ontbreekt) met `server.forward-headers-strategy=none`, en een vertrouwde proxy die de client niet is.
 * Een client die zich als de proxy voordoet via `X-Forwarded-For` of `X-Forwarded-Host` krijgt geen andere tenant.
 */
@SpringBootTest(
    classes = [TestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.main.cloud-platform=kubernetes",
        "server.forward-headers-strategy=none",
        "nl-portal.tenancy.trusted-proxies=10.1.2.3",
    ],
)
@Tag("integration")
class ForwardedHeadersIT(
    @LocalServerPort private val serverPort: Int,
) {
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
    fun `een client die zich met X-Forwarded-For als de proxy voordoet krijgt geen tenant via X-Forwarded-Host`() {
        get("onbekend.example", "X-Forwarded-For" to "10.1.2.3", "X-Forwarded-Host" to "bosrand.example")
            .expectStatus()
            .isNotFound
            .expectBody()
            .isEmpty
        get("onbekend.example", "X-Forwarded-For" to "10.1.2.3", "Forwarded" to "for=10.1.2.3;host=bosrand.example;proto=https")
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `een request zonder Host-header met X-Forwarded-Host krijgt 404`() {
        val antwoord =
            Socket("localhost", serverPort).use { socket ->
                socket.soTimeout = 10_000
                socket
                    .getOutputStream()
                    .write("GET /api/public/tenant-probe HTTP/1.0\r\nX-Forwarded-Host: bosrand.example\r\nX-Forwarded-For: 10.1.2.3\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes().decodeToString()
            }

        assertThat(antwoord.lineSequence().first()).contains(" 404")
    }

    @Test
    fun `een bekende host werkt ook met cloudplatform kubernetes`() {
        get("bosrand.example")
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo("00000099000000000001")
    }
}