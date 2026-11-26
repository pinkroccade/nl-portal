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
package nl.nlportal.zgw.taak.graphql

import nl.nlportal.commonground.authentication.WithBurgerUser
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.zgw.taak.TAAK_MODULE
import nl.nlportal.zgw.taak.TaakFlagFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.graphql.test.tester.HttpGraphQlTester
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/** De centrale gate op de taakmodule: elke query en de uploadresource weigeren als de flag van de tenant uit staat. */
@SpringBootTest
@AutoConfigureHttpGraphQlTester
@AutoConfigureWebTestClient(timeout = "36000")
@Tag("integration")
internal class TaakModuleGateIT(
    @Autowired private val httpGraphQlTester: HttpGraphQlTester,
    @Autowired private val webTestClient: WebTestClient,
    @Autowired private val moduleGate: ModuleGate,
) {
    @AfterEach
    fun herstel() = TaakFlagFile.vlaggen.herstel(moduleGate)

    @Test
    @WithBurgerUser("569312863")
    fun `every taak query is not available when the flag is off`() {
        TaakFlagFile.vlaggen.zet(TAAK_MODULE, aan = false, gate = moduleGate)

        listOf(
            "query { getTakenV2(pageNumber: 1, pageSize: 10) { totalElements } }",
            "query { getTaakByIdV2(id: \"58fad5ab-dc2f-11ec-9075-f22a405ce707\") { id } }",
        ).forEach { query ->
            httpGraphQlTester
                .document(query)
                .execute()
                .errors()
                .expect { it.message == "Module 'taak' is niet beschikbaar" && it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
                .verify()
        }
    }

    @Test
    @WithBurgerUser("569312863")
    fun `a taak query is not available for a tenant the flag is not on for`() {
        TaakFlagFile.vlaggen.zet(TAAK_MODULE, aan = true, gate = moduleGate, alleenVoor = setOf(Oin("00000099000000000002")))

        httpGraphQlTester
            .document("query { getTakenV2(pageNumber: 1, pageSize: 10) { totalElements } }")
            .execute()
            .errors()
            .expect { it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    @Test
    @WithBurgerUser("569312863")
    fun `the upload resource answers 503 when the flag is off`() {
        TaakFlagFile.vlaggen.zet(TAAK_MODULE, aan = false, gate = moduleGate)

        webTestClient
            .post()
            .uri("/api/taak/58fad5ab-dc2f-11ec-9075-f22a405ce707/document/content")
            .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
            .exchange()
            .expectStatus()
            .isEqualTo(503)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun featureFlags(registry: DynamicPropertyRegistry) = TaakFlagFile.vlaggen.registreer(registry)
    }
}