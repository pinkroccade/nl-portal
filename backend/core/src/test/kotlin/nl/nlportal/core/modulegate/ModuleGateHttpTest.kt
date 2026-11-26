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
package nl.nlportal.core.modulegate

import nl.nlportal.core.testing.TestTenants
import nl.nlportal.testing.TweeTenantsConfiguratie
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * De centrale gate op de applicatiegrens, met twee tenants (Bosrand en Wijde Veld) die elk op hun eigen host binnenkomen: de gate
 * beslist per tenant, weigert een endpoint zonder registratie (deny by default), een module zonder configuratie van de tenant en een
 * module waarvan de flag uit staat, en laat de kern en een module in overgang door.
 */
@SpringBootTest(properties = ["nl-portal.test.eigen-tenants=true"])
@AutoConfigureWebTestClient(timeout = "36000")
@Import(ModuleGateHttpTest.Opstelling::class, TweeTenantsConfiguratie::class)
class ModuleGateHttpTest(
    @Autowired private val client: WebTestClient,
) {
    @TestConfiguration
    class Opstelling {
        @Bean
        fun voorbeeldModule() = VOORBEELD

        @Bean
        fun zonderConfiguratieModule() = ZONDER_CONFIGURATIE

        @ModuleEndpoint("voorbeeld")
        @RestController
        class VoorbeeldResource {
            @GetMapping("/gate-test/voorbeeld")
            fun get() = "voorbeeld"
        }

        @ModuleEndpoint("zonderconfiguratie")
        @RestController
        class ZonderConfiguratieResource {
            @GetMapping("/gate-test/zonderconfiguratie")
            fun get() = "zonderconfiguratie"
        }

        @ModuleEndpoint(ModuleEndpoint.KERN)
        @RestController
        class KernResource {
            @GetMapping("/gate-test/kern")
            fun get() = "kern"
        }

        @ModuleEndpoint("oud", overgang = true)
        @RestController
        class OvergangResource {
            @GetMapping("/gate-test/overgang")
            fun get() = "overgang"
        }

        @RestController
        class OngeregistreerdeResource {
            @GetMapping("/gate-test/ongeregistreerd")
            fun get() = "ongeregistreerd"
        }
    }

    private fun get(
        pad: String,
        host: String,
    ) = client
        .get()
        .uri(pad)
        .header("Host", host)
        .exchange()

    @Test
    fun `a module is reachable for the tenant whose flag is on and answers 503 for the other`() {
        schrijfVlaggen(voorbeeldVoor = TestTenants.BOSRAND.value)

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            get("/gate-test/voorbeeld", BOSRAND_HOST).expectStatus().isOk
            get("/gate-test/voorbeeld", WIJDE_VELD_HOST)
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Module 'voorbeeld' is niet beschikbaar")
        }
    }

    @Test
    fun `a flag change before the restart moves the module to the other tenant and never opens a module without configuration`() {
        schrijfVlaggen(voorbeeldVoor = TestTenants.WIJDE_VELD.value)

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            get("/gate-test/voorbeeld", WIJDE_VELD_HOST).expectStatus().isOk
            get("/gate-test/voorbeeld", BOSRAND_HOST).expectStatus().isEqualTo(503)
        }
        // De flag staat voor beide tenants aan, maar de tenants hebben de koppeling 'ontbrekend' niet: uit.
        listOf(BOSRAND_HOST, WIJDE_VELD_HOST).forEach { get("/gate-test/zonderconfiguratie", it).expectStatus().isEqualTo(503) }
    }

    @Test
    fun `an unregistered endpoint is denied, the core and a transitional module are let through`() {
        schrijfVlaggen(voorbeeldVoor = TestTenants.BOSRAND.value)

        listOf(BOSRAND_HOST, WIJDE_VELD_HOST).forEach { host ->
            get("/gate-test/ongeregistreerd", host).expectStatus().isNotFound
            get("/gate-test/kern", host).expectStatus().isOk
            get("/gate-test/overgang", host).expectStatus().isOk
        }
    }

    companion object {
        const val BOSRAND_HOST = TweeTenantsConfiguratie.BOSRAND_HOST
        const val WIJDE_VELD_HOST = TweeTenantsConfiguratie.WIJDE_VELD_HOST
        val VOORBEELD = ModuleFlag("voorbeeld")
        val ZONDER_CONFIGURATIE = ModuleFlag("zonderconfiguratie", koppelingen = setOf("ontbrekend"))

        private val vlaggen: Path = Files.createTempFile("gate-flags", ".flagd.json")

        /** `voorbeeld` is alleen aan voor de tenant [voorbeeldVoor]; `zonderconfiguratie` staat voor elke tenant aan. */
        private fun schrijfVlaggen(voorbeeldVoor: String) {
            Files.writeString(
                vlaggen,
                """
                {
                  "${'$'}schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "${VOORBEELD.flag.key}": {
                      "state": "ENABLED",
                      "variants": { "on": true, "off": false },
                      "defaultVariant": "off",
                      "targeting": { "if": [{ "==": [{ "var": "tenant" }, "$voorbeeldVoor"] }, "on", "off"] }
                    },
                    "${ZONDER_CONFIGURATIE.flag.key}": {
                      "state": "ENABLED",
                      "variants": { "on": true, "off": false },
                      "defaultVariant": "on"
                    }
                  }
                }
                """.trimIndent(),
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun featureFlagProperties(registry: DynamicPropertyRegistry) {
            schrijfVlaggen(voorbeeldVoor = TestTenants.BOSRAND.value)
            registry.add("nl-portal.feature-flags.file") { vlaggen.toString() }
            registry.add("nl-portal.feature-flags.poll-interval-ms") { 200 }
        }
    }
}