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
package nl.nlportal.haalcentraalbrp.graphql

import tools.jackson.databind.JsonNode
import nl.nlportal.commonground.authentication.WithBurgerUser
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.ModuleFlagBestand
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.haalcentraalbrp.tenant.BrpConfiguratie
import nl.nlportal.haalcentraalbrp.BRP_MODULE
import nl.nlportal.haalcentraalbrp.TestHelper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureHttpGraphQlTester
@AutoConfigureWebTestClient(timeout = "36000")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
internal class HaalCentraalBrpQueryIT(
    @Autowired private val httpGraphQlTester: HttpGraphQlTester,
    @Autowired private val moduleGate: ModuleGate,
) {
    companion object {
        internal val passThroughHeaders =
            mapOf(
                "x-origin-oin" to "test-origin-oin",
                "x-doelbinding" to "test-doelbinding",
                "x-verwerking" to "test-verwerking",
                "x-gebruiker" to "test-gebruiker",
            )

        /** Eén flagbestand voor alle testcontexts van deze module (OpenFeature is een JVM-brede singleton). */
        private val vlaggen = ModuleFlagBestand(listOf(BRP_MODULE))

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

    /** Eén tenant met koppelingen naar de mockserver; elk request draait als die tenant (zoals de tenantresolutie dat doet). */
    @TestConfiguration
    class TenantConfiguration {
        @Bean
        fun tenantDirectory(): TenantDirectory {
            val basis = server.url("/").toString().trimEnd('/')
            return TestTenants.directory(
                TestTenants.tenant(
                    koppelingen =
                        mapOf(
                            "brp" to TestTenants.koppeling("$basis/brp", TenantKoppelingAuth(TenantKoppelingAuthType.GEEN)),
                            "brp-bewoning" to TestTenants.koppeling("$basis/bewoning", TenantKoppelingAuth(TenantKoppelingAuthType.GEEN)),
                        ),
                    modules =
                        mapOf(
                            "brp" to
                                BrpConfiguratie(
                                    toegestaneWoonplaatsenBewoning = listOf("'s-Gravenhage", "Ypenburg"),
                                    aanvullendeHeaders = passThroughHeaders,
                                ),
                        ),
                ),
            )
        }

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
    @WithBurgerUser("999993847")
    fun `getPersoonV2 is niet beschikbaar als de modulevlag uit staat`() {
        vlaggen.zet(BRP_MODULE, aan = false, gate = moduleGate)

        httpGraphQlTester
            .document("query { getPersoonV2 { burgerservicenummer } }")
            .execute()
            .errors()
            .expect { it.message == "Module 'haalcentraal-brp' is niet beschikbaar" && it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    @Test
    @WithBurgerUser("999993847")
    fun `getPersoonV2 is niet beschikbaar voor een tenant waarvoor de vlag uit staat`() {
        vlaggen.zet(BRP_MODULE, aan = true, gate = moduleGate, alleenVoor = setOf(TestTenants.WIJDE_VELD))

        httpGraphQlTester
            .document("query { getPersoonV2 { burgerservicenummer } }")
            .execute()
            .errors()
            .expect { it.extensions["code"] == "MODULE_NOT_AVAILABLE" }
            .verify()
    }

    @BeforeEach
    internal fun setUp() {
        // De server is gedeeld: leeg de wachtrij, anders leest takeRequest() het verzoek van een eerdere test.
        while (server.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // leegmaken
        }
        setupMockServer()
    }

    @Test
    @WithBurgerUser("999993847")
    fun getPersoon() {
        val query =
            """
            query {
                getPersoonV2 {
                    burgerservicenummer,
                    naam {
                        geslachtsnaam,
                        volledigeNaam,
                        lastName,
                        officialLastName,
                    }
                    leeftijd,
                    nationaliteiten {
                        type,
                        redenOpname {
                            code,
                            omschrijving
                        },
                        nationaliteit {
                            code,
                            omschrijving
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
                .path("getPersoonV2")
                .entity(JsonNode::class.java)
                .get()

        assertEquals("999993847", responseBody.get("burgerservicenummer").stringValue())
        assertEquals("Pieter Jan de Vries", responseBody.requiredAt("/naam/volledigeNaam")?.stringValue())
        assertEquals("Vries", responseBody.requiredAt("/naam/geslachtsnaam")?.stringValue())
        val verzoek = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/brp/personen", verzoek.path)
        assertEquals("test-doelbinding", verzoek.getHeader("x-doelbinding"))
        assertEquals("application/json", verzoek.getHeader("Accept"))
        assertNull(verzoek.getHeader("X-Api-Key"))
        assertNull(verzoek.getHeader("Authorization"))
    }

    @Test
    @WithBurgerUser("999993847")
    fun getPersoonWithBewonersAantal() {
        val query =
            """
            query {
                getPersoonV2 {
                    burgerservicenummer,
                    naam {
                        geslachtsnaam,
                        volledigeNaam,
                    },
                    bewonersAantal
                }
            }
            """.trimIndent()

        val responseBody =
            httpGraphQlTester
                .document(query)
                .execute()
                .errors()
                .verify()
                .path("getPersoonV2")
                .entity(JsonNode::class.java)
                .get()

        assertEquals("999993847", responseBody.get("burgerservicenummer").stringValue())
        assertEquals(4, responseBody.get("bewonersAantal").intValue())
    }

    @Test
    @WithBurgerUser("999993847")
    fun getContactGegevens() {
        val query =
            """
            query {
                getPersoonV2 {
                    burgerservicenummer,
                    datumEersteInschrijvingGBA {
                        type,
                        datum,
                        langFormaat
                    },
                    geheimhoudingPersoonsgegevens,
                    naam {
                        geslachtsnaam,
                        volledigeNaam,
                    },
                    verblijfplaats {
                        adresseerbaarObjectIdentificatie,
                        verblijfadres {
                            officieleStraatnaam,
                            huisnummer,
                            huisletter,
                            huisnummertoevoeging,
                            postcode,
                            woonplaats
                        }
                    },
                    geboorte {
                        datum {
                            type,
                            datum,
                            langFormaat
                        },
                        land {
                            code,
                            omschrijving
                        },
                        plaats {
                            code,
                            omschrijving
                        }
                    },
                    nationaliteiten {
                        nationaliteit {
                            omschrijving
                        }
                    },
                    kinderen{
                        naam {
                            geslachtsnaam
                        },
                        geboorte {
                            datum {
                                type,
                                datum,
                                langFormaat
                            },
                            land {
                                code,
                                omschrijving
                            },
                            plaats {
                                code,
                                omschrijving
                            }
                        }
                    },
                    partners{
                        naam {
                            geslachtsnaam
                        }
                    },
                    ouders{
                        naam {
                            geslachtsnaam
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
                .path("getPersoonV2")
                .entity(JsonNode::class.java)
                .get()

        assertEquals("999993847", responseBody.get("burgerservicenummer").stringValue())
        assertEquals(true, responseBody.get("geheimhoudingPersoonsgegevens").booleanValue())
        assertEquals("226010000038820", responseBody.requiredAt("/verblijfplaats/adresseerbaarObjectIdentificatie")?.stringValue())
        assertEquals("Het Spui 1", responseBody.requiredAt("/verblijfplaats/verblijfadres/officieleStraatnaam")?.stringValue())
        assertEquals("Nederlands", responseBody.requiredAt("/nationaliteiten/0/nationaliteit/omschrijving")?.stringValue())
    }

    private fun setupMockServer() {
        val dispatcher: Dispatcher =
            object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?')
                    val response =
                        when (request.method + " " + path) {
                            "POST /brp/personen" -> {
                                TestHelper.mockResponseFromFile("/data/get-personen.json")
                            }

                            "POST /bewoning/bewoningen" -> {
                                TestHelper.mockResponseFromFile("/data/get-bewoningen.json")
                            }

                            else -> {
                                MockResponse().setResponseCode(404)
                            }
                        }
                    return response
                }
            }
        server.dispatcher = dispatcher
    }
}