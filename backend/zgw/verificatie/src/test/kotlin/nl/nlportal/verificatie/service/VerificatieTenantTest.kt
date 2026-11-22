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
package nl.nlportal.verificatie.service

import java.util.UUID
import kotlinx.coroutines.runBlocking
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.verificatie.graphql.domain.VerificatieCreateInput
import nl.nlportal.verificatie.graphql.domain.VerificatieType
import nl.nlportal.verificatie.graphql.domain.VerificatieVerifyInput
import nl.nlportal.verificatie.tenant.VerificatieConfiguratie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Seam 1 (epic #499, T20): het e-mailadres, telefoonnummer en de code gaan alleen naar de koppeling van de eigen tenant. */
internal class VerificatieTenantTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD
    private lateinit var serverBosrand: MockWebServer
    private lateinit var serverWijdeVeld: MockWebServer

    @BeforeEach
    fun setUp() {
        serverBosrand = MockWebServer().also { it.start() }
        serverWijdeVeld = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        serverBosrand.shutdown()
        serverWijdeVeld.shutdown()
    }

    @Test
    fun `een e-mailadres van Bosrand gaat alleen naar de verificatiebron van Bosrand, met eigen sleutel en template`() {
        serverBosrand.enqueue(succes())
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.createVerificatie(createInput(VerificatieType.EMAIL, "inwoner@bosrand.example")) }

        assertThat(resultaat.success).isTrue()
        assertThat(serverWijdeVeld.requestCount).isZero()
        val verzoek = serverBosrand.takeRequest()
        assertThat(verzoek.method).isEqualTo("POST")
        assertThat(verzoek.path).isEqualTo("/api/verification-requests")
        val body = verzoek.body.readUtf8()
        assertThat(body).contains("\"email\":\"inwoner@bosrand.example\"")
        assertThat(body).contains("\"apiKey\":\"sleutel-bosrand\"").contains("\"templateId\":\"mail-bosrand\"")
        assertThat(body).doesNotContain("phoneNumber").doesNotContain("wijde-veld")
        assertThat(verzoek.getHeader("Authorization")).startsWith("Bearer ")
        assertThat(verzoek.getHeader("Accept")).isEqualTo("application/json")
    }

    @Test
    fun `een telefoonnummer van Wijde Veld gaat alleen naar de bron van Wijde Veld met het sms-template`() {
        serverWijdeVeld.enqueue(succes())
        val service = service()

        TestTenants.metTenant(wijdeVeld) { service.createVerificatie(createInput(VerificatieType.TELEFOONNUMMER, "0612345678")) }

        assertThat(serverBosrand.requestCount).isZero()
        val body = serverWijdeVeld.takeRequest().body.readUtf8()
        assertThat(body).contains("\"phoneNumber\":\"0612345678\"")
        assertThat(body).contains("\"apiKey\":\"sleutel-wijde-veld\"").contains("\"templateId\":\"sms-wijde-veld\"")
        assertThat(body).doesNotContain("bosrand")
    }

    @Test
    fun `de code van een verificatie gaat alleen naar de bron van de eigen tenant`() {
        serverWijdeVeld.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"verified":true,"verifiedOn":"2026-11-22T10:00:00Z"}"""),
        )
        val service = service()

        val resultaat =
            TestTenants.metTenant(wijdeVeld) {
                service.verify(VerificatieVerifyInput(uuid = UUID.randomUUID(), waarde = "a@wijde-veld.example", type = VerificatieType.EMAIL, code = "123456"))
            }

        assertThat(resultaat.verified).isTrue()
        assertThat(serverBosrand.requestCount).isZero()
        val verzoek = serverWijdeVeld.takeRequest()
        assertThat(verzoek.path).isEqualTo("/api/verification-requests/verify")
        assertThat(verzoek.body.readUtf8()).contains("\"code\":\"123456\"").contains("a@wijde-veld.example")
    }

    @Test
    fun `welke typen verificatie vereisen volgt de configuratie van de eigen tenant`() {
        val service = service()

        assertThat(TestTenants.metTenant(bosrand) { service.vereistVerificatie(VerificatieType.EMAIL) }).isTrue()
        assertThat(TestTenants.metTenant(bosrand) { service.vereistVerificatie(VerificatieType.TELEFOONNUMMER) }).isFalse()
        assertThat(TestTenants.metTenant(wijdeVeld) { service.vereistVerificatie(VerificatieType.TELEFOONNUMMER) }).isTrue()
        assertThat(TestTenants.metTenant(wijdeVeld) { service.vereistVerificatie(VerificatieType.EMAIL) }).isFalse()
    }

    @Test
    fun `een bronfout geeft een mislukte respons zonder bronwaarden of waarde van de inwoner`() {
        serverBosrand.enqueue(MockResponse().setResponseCode(500).setBody("kapot voor inwoner@bosrand.example"))
        val service = service()

        val resultaat = TestTenants.metTenant(bosrand) { service.createVerificatie(createInput(VerificatieType.EMAIL, "inwoner@bosrand.example")) }

        assertThat(resultaat.success).isFalse()
        assertThat(resultaat.errorMessage).doesNotContain("inwoner@bosrand.example").doesNotContain("kapot")
    }

    @Test
    fun `zonder tenant faalt elke operatie gesloten en gaat er niets naar een bron`() {
        val service = service()

        assertThrows<TenantContextMissingException> { runBlocking { service.createVerificatie(createInput(VerificatieType.EMAIL, "a@b.example")) } }
        assertThrows<TenantContextMissingException> {
            runBlocking { service.verify(VerificatieVerifyInput(waarde = "a@b.example", type = VerificatieType.EMAIL, code = "1")) }
        }
        assertThrows<TenantContextMissingException> { runBlocking { service.vereistVerificatie(VerificatieType.EMAIL) } }
        assertThat(serverBosrand.requestCount).isZero()
        assertThat(serverWijdeVeld.requestCount).isZero()
    }

    @Test
    fun `een tenant zonder verificatieconfiguratie faalt gesloten op elke operatie`() {
        val zonder = TestTenants.tenant(bosrand, koppelingen = mapOf("verificatie" to koppeling(serverBosrand, bosrand)))
        val service = VerificatieService(TestTenants.register(zonder), TestTenants.directory(zonder), TestTenants.ALLE_GEHEIMEN)

        assertThrows<ModuleNotConfiguredException> {
            TestTenants.metTenant(bosrand) { service.createVerificatie(createInput(VerificatieType.EMAIL, "a@b.example")) }
        }
        assertThrows<ModuleNotConfiguredException> {
            TestTenants.metTenant(bosrand) { service.verify(VerificatieVerifyInput(waarde = "a@b.example", type = VerificatieType.EMAIL, code = "1")) }
        }
        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(bosrand) { service.vereistVerificatie(VerificatieType.EMAIL) } }
        assertThat(serverBosrand.requestCount).isZero()
    }

    private fun createInput(
        type: VerificatieType,
        waarde: String,
    ) = VerificatieCreateInput(uuid = UUID.randomUUID(), waarde = waarde, type = type)

    private fun succes() = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true}""")

    private fun koppeling(
        server: MockWebServer,
        oin: Oin,
    ) = TestTenants.zgwKoppeling(server.url("/").toString().trimEnd('/'), clientId = "nl-portal", geheimnaam = "verificatie-secret", oin = oin)

    private fun configuratie(
        oin: Oin,
        mail: String,
        sms: String,
        typen: List<VerificatieType>,
    ) = VerificatieConfiguratie(
        apiKey = TestTenants.geheim("verificatie-apikey", oin),
        templateIdEmail = mail,
        templateIdTelefoonnummer = sms,
        typenMetVerificatie = typen,
    )

    private fun service(): VerificatieService {
        val tenantBosrand =
            TestTenants.tenant(
                bosrand,
                koppelingen = mapOf("verificatie" to koppeling(serverBosrand, bosrand)),
                modules = mapOf("verificatie" to configuratie(bosrand, "mail-bosrand", "sms-bosrand", listOf(VerificatieType.EMAIL))),
            )
        val tenantWijdeVeld =
            TestTenants.tenant(
                wijdeVeld,
                koppelingen = mapOf("verificatie" to koppeling(serverWijdeVeld, wijdeVeld)),
                modules = mapOf("verificatie" to configuratie(wijdeVeld, "mail-wijde-veld", "sms-wijde-veld", listOf(VerificatieType.TELEFOONNUMMER))),
            )
        val geheimen =
            TestTenants.geheimen(
                TestTenants.geheim("verificatie-secret", bosrand) to "geheim-van-bosrand-minstens-32-tekens-lang",
                TestTenants.geheim("verificatie-secret", wijdeVeld) to "geheim-van-wijde-veld-minstens-32-tekens-lang",
                TestTenants.geheim("verificatie-apikey", bosrand) to "sleutel-bosrand",
                TestTenants.geheim("verificatie-apikey", wijdeVeld) to "sleutel-wijde-veld",
            )
        return VerificatieService(
            TestTenants.register(tenantBosrand, tenantWijdeVeld, geheimen = geheimen),
            TestTenants.directory(tenantBosrand, tenantWijdeVeld),
            geheimen,
        )
    }
}