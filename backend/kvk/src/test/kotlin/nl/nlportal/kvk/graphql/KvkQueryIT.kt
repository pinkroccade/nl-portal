/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.kvk.graphql

import nl.nlportal.commonground.authentication.WithBedrijfUser
import nl.nlportal.kvk.KVK_MODULE
import nl.nlportal.kvk.TestHelper
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.testing.ModuleFlagBestand
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.graphql.test.tester.HttpGraphQlTester
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode

@SpringBootTest
@AutoConfigureHttpGraphQlTester
@AutoConfigureWebTestClient(timeout = "36000")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
internal class KvkQueryIT(
    @Autowired private val httpGraphQlTester: HttpGraphQlTester,
    @Autowired private val moduleGate: ModuleGate,
) {
    companion object {
        /** Eén flagbestand voor alle testcontexts van deze module (OpenFeature is een JVM-brede singleton). */
        internal val vlaggen = ModuleFlagBestand(listOf(KVK_MODULE))

        @JvmStatic
        @DynamicPropertySource
        fun featureFlags(registry: DynamicPropertyRegistry) = vlaggen.registreer(registry)

        @JvmStatic
        val server: MockWebServer = MockWebServer().also { it.start() }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            server.shutdown()
        }
    }

    /** Eén tenant met een KvK-koppeling naar de mockserver; elk request draait als die tenant (zoals de tenantresolutie dat doet). */
    @TestConfiguration
    class TenantConfiguration {
        @Bean
        fun tenantDirectory(): TenantDirectory =
            TestTenants.directory(
                TestTenants.tenant(
                    koppelingen =
                        mapOf(
                            "kvk" to
                                TestTenants.koppeling(
                                    server.url("/").toString().trimEnd('/'),
                                    TenantKoppelingAuth(TenantKoppelingAuthType.API_KEY, headerNaam = "X-API-KEY", waarde = TestTenants.geheim("kvk-sleutel")),
                                ),
                        ),
                ),
            )

        @Bean
        fun geheimenBron(): GeheimenBron = TestTenants.ALLE_GEHEIMEN

        @Bean
        fun vasteTenantWebFilter() = VasteTenantWebFilter()
    }

    @AfterEach
    internal fun herstelFlag() {
        vlaggen.herstel(moduleGate)
    }

    @Test
    @WithBedrijfUser(kvkNummer = "90012768")
    fun `getBedrijf is niet beschikbaar als de modulevlag uit staat`() {
        vlaggen.zet(KVK_MODULE, aan = false, gate = moduleGate)

        httpGraphQlTester
            .document("query { getBedrijf { naam } }")
            .execute()
            .errors()
            .expect { it.message == "Module 'kvk' is niet beschikbaar" && it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    @Test
    @WithBedrijfUser(kvkNummer = "90012768")
    fun `getBedrijf is niet beschikbaar voor een tenant waarvoor de vlag uit staat`() {
        vlaggen.zet(KVK_MODULE, aan = true, gate = moduleGate, alleenVoor = setOf(TestTenants.WIJDE_VELD))

        httpGraphQlTester
            .document("query { getBedrijf { naam } }")
            .execute()
            .errors()
            .expect { it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    @BeforeEach
    internal fun setUp() {
        setupMockServer()
    }

    @Test
    @WithBedrijfUser(kvkNummer = "90012768")
    fun getKvkData() {
        val query =
            """
            query {
                getBedrijf {
                    naam
                    embedded{
                        eigenaar {
                            rechtsvorm
                        }
                        vestiging {
                            eersteHandelsnaam
                            adressen {
                                straatnaam
                                huisnummer
                                postbusnummer
                                postcode
                                plaats
                                volledigAdres
                            }
                        }
                    }
                }
            }
            """.trimIndent()

        val responseBody =
            httpGraphQlTester
                .document(query)
                .execute()
                .errors()
                .verify()
                .path("getBedrijf")
                .entity(JsonNode::class.java)
                .get()

        assertEquals("Test bedrijf", responseBody.get("naam").stringValue())
        assertEquals("Eenmanszaak", responseBody.requiredAt("/embedded/eigenaar/rechtsvorm")?.stringValue())
        assertEquals("Test bedrijf", responseBody.requiredAt("/embedded/vestiging/eersteHandelsnaam")?.stringValue())
        assertEquals("Postbus 1000 2260BA LEIDSCHENDAM", responseBody.requiredAt("/embedded/vestiging/adressen/0/volledigAdres")?.stringValue())
    }

    @Test
    @WithBedrijfUser(
        kvkNummer = "90012768",
        vestigingsNummer = "990000262129",
    )
    fun getKvkDataWithVestiging() {
        val query =
            """
            query {
                getBedrijf {
                    naam
                    embedded{
                        eigenaar {
                            rechtsvorm
                        }
                        vestiging {
                            eersteHandelsnaam
                            adressen {
                                straatnaam
                                huisnummer
                                postbusnummer
                                postcode
                                plaats
                                volledigAdres
                            }
                        }
                    }
                }
            }
            """.trimIndent()

        val responseBody =
            httpGraphQlTester
                .document(query)
                .execute()
                .errors()
                .verify()
                .path("getBedrijf")
                .entity(JsonNode::class.java)
                .get()

        assertEquals("Test bedrijf", responseBody.get("naam").stringValue())
        assertEquals("Eenmanszaak", responseBody.requiredAt("/embedded/eigenaar/rechtsvorm")?.stringValue())
        assertEquals("Regional Sanjoflex B.V.", responseBody.requiredAt("/embedded/vestiging/eersteHandelsnaam")?.stringValue())
        assertEquals("Maarten Trompstraat 2 6372VR Landgraaf", responseBody.requiredAt("/embedded/vestiging/adressen/0/volledigAdres")?.stringValue())
    }

    @Test
    @WithBedrijfUser(
        kvkNummer = "90012768",
        vestigingsNummer = "990000262128",
    )
    fun getKvkDataWithVestigingNotFound() {
        val query =
            """
            query {
                getBedrijf {
                    naam
                    embedded{
                        eigenaar {
                            rechtsvorm
                        }
                        vestiging {
                            eersteHandelsnaam
                            adressen {
                                straatnaam
                                huisnummer
                                postbusnummer
                                postcode
                                plaats
                                volledigAdres
                            }
                        }
                    }
                }
            }
            """.trimIndent()

        val responseBody =
            httpGraphQlTester
                .document(query)
                .execute()
                .errors()
                .verify()
                .path("getBedrijf")
                .entity(JsonNode::class.java)
                .get()

        assertEquals("Test bedrijf", responseBody.get("naam").stringValue())
        assertEquals("Eenmanszaak", responseBody.requiredAt("/embedded/eigenaar/rechtsvorm")?.stringValue())
        assertEquals("Test bedrijf", responseBody.requiredAt("/embedded/vestiging/eersteHandelsnaam")?.stringValue())
        assertEquals("Postbus 1000 2260BA LEIDSCHENDAM", responseBody.requiredAt("/embedded/vestiging/adressen/0/volledigAdres")?.stringValue())
    }

    private fun setupMockServer() {
        val dispatcher: Dispatcher =
            object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response =
                        when (request.path?.substringBefore('?')) {
                            "/basisprofielen/90012768" -> TestHelper.mockResponseFromFile("/data/get-maatschappelijke-activiteiten.json")
                            "/vestigingsprofielen/990000262129" -> TestHelper.mockResponseFromFile("/data/get-vestiging.json")
                            else -> MockResponse().setResponseCode(404)
                        }
                    return response
                }
            }
        server.dispatcher = dispatcher
    }
}