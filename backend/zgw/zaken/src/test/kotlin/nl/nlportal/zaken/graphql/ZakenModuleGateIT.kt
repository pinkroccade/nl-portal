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
package nl.nlportal.zaken.graphql

import nl.nlportal.commonground.authentication.WithBurgerUser
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.documentenapi.DOCUMENTENAPIS_MODULE
import nl.nlportal.zaken.ZAKEN_MODULE
import nl.nlportal.zaken.ZakenFlagFile
import nl.nlportal.zaken.ZakenTestTenantConfiguratie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.graphql.test.tester.HttpGraphQlTester
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/** De centrale gate op de zakenmodule: elke query en de documentdownload weigeren als de flag van de tenant uit staat. */
@SpringBootTest
@AutoConfigureHttpGraphQlTester
@AutoConfigureWebTestClient(timeout = "36000")
@Tag("integration")
internal class ZakenModuleGateIT(
    @Autowired private val httpGraphQlTester: HttpGraphQlTester,
    @Autowired private val webTestClient: WebTestClient,
    @Autowired private val moduleGate: ModuleGate,
) {
    @TestConfiguration
    class Tenants : ZakenTestTenantConfiguratie({ "http://localhost:8001" })

    @AfterEach
    fun herstel() = ZakenFlagFile.vlaggen.herstel(moduleGate)

    @Test
    @WithBurgerUser("569312863")
    fun `every zaken query is not available when the flag is off`() {
        ZakenFlagFile.vlaggen.zet(ZAKEN_MODULE, aan = false, gate = moduleGate)

        listOf(
            "query { getZaken(page: 1) { totalElements } }",
            "query { getZaak(id: \"58fad5ab-dc2f-11ec-9075-f22a405ce707\") { uuid } }",
        ).forEach { query ->
            httpGraphQlTester
                .document(query)
                .execute()
                .errors()
                .expect { it.message == "Module 'zaken' is niet beschikbaar" && it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
                .verify()
        }
    }

    @Test
    @WithBurgerUser("569312863")
    fun `a zaken query is not available for a tenant the flag is not on for`() {
        ZakenFlagFile.vlaggen.zet(ZAKEN_MODULE, aan = true, gate = moduleGate, alleenVoor = setOf(Oin("00000099000000000002")))

        httpGraphQlTester
            .document("query { getZaken(page: 1) { totalElements } }")
            .execute()
            .errors()
            .expect { it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    @Test
    @WithBurgerUser("569312863")
    fun `a zaken query is not available when the required documentenapis flag is off`() {
        ZakenFlagFile.vlaggen.zet(DOCUMENTENAPIS_MODULE, aan = false, gate = moduleGate)

        httpGraphQlTester
            .document("query { getZaken(page: 1) { totalElements } }")
            .execute()
            .errors()
            .expect { it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    @Test
    @WithBurgerUser("569312863")
    fun `the document resource answers 503 when the flag is off`() {
        ZakenFlagFile.vlaggen.zet(ZAKEN_MODULE, aan = false, gate = moduleGate)

        webTestClient
            .get()
            .uri("/api/zakenapi/zaakdocument/58fad5ab-dc2f-11ec-9075-f22a405ce707/content")
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun featureFlags(registry: DynamicPropertyRegistry) = ZakenFlagFile.vlaggen.registreer(registry)
    }
}