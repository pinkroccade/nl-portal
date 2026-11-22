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
package nl.nlportal.openklant.graphql

import java.util.UUID
import kotlinx.coroutines.runBlocking
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.openklant.client.domain.OpenKlant2DigitaleAdres
import nl.nlportal.openklant.graphql.domain.DigitaleAdresRequest
import nl.nlportal.openklant.graphql.domain.DigitaleAdresType
import nl.nlportal.openklant.service.OpenKlant2Service
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.verificatie.graphql.domain.VerificatieType
import nl.nlportal.verificatie.service.VerificatieService
import nl.nlportal.verificatie.tenant.VerificatieConfiguratie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Verificatie is per tenant: digitale adressen lezen en opslaan werkt zonder `modules.verificatie`, en volgt de eigen tenant met. */
internal class DigitaleAdresControllersTenantTest {
    private val zonderVerificatie = TestTenants.BOSRAND
    private val metVerificatie = TestTenants.WIJDE_VELD
    private lateinit var bron: MockWebServer
    private val openklant = mock<OpenKlant2Service>()
    private val authenticatie = mock<PortalAuthentication>()
    private val adres = OpenKlant2DigitaleAdres(uuid = UUID.randomUUID(), adres = "a@b.example", omschrijving = "werk", soortDigitaalAdres = "email")
    private val verzoek = DigitaleAdresRequest(uuid = adres.uuid, waarde = "a@b.example", type = DigitaleAdresType.EMAIL, omschrijving = "werk")

    @BeforeEach
    fun setUp() {
        bron = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        bron.shutdown()
    }

    private fun verificatieService(): VerificatieService {
        val zonder = TestTenants.tenant(zonderVerificatie)
        val met =
            TestTenants.tenant(
                metVerificatie,
                koppelingen = mapOf("verificatie" to TestTenants.zgwKoppeling(bron.url("/").toString().trimEnd('/'), clientId = "nl-portal", geheimnaam = "verificatie-secret", oin = metVerificatie)),
                modules = mapOf("verificatie" to VerificatieConfiguratie(typenMetVerificatie = listOf(VerificatieType.EMAIL))),
            )
        return VerificatieService(TestTenants.register(zonder, met), TestTenants.directory(zonder, met), TestTenants.ALLE_GEHEIMEN)
    }

    @Test
    fun `een tenant zonder verificatie leest zijn digitale adressen zonder fout`() {
        runBlocking { whenever(openklant.findDigitaleAdressen(any(), any(), any())).thenReturn(listOf(adres)) }
        val query = DigitaleAdresQuery(openklant, verificatieService())

        val antwoord = TestTenants.metTenant(zonderVerificatie) { query.getUserDigitaleAdressen(authenticatie, false) }

        assertThat(antwoord).singleElement().satisfies({ assertThat(it.verificatieNeeded).isFalse() })
    }

    @Test
    fun `een tenant zonder verificatie slaat een digitaal adres op zonder verificatiestroom`() {
        runBlocking { whenever(openklant.createDigitaleAdres(any(), any())).thenReturn(adres) }
        val mutatie = DigitaleAdresMutation(openklant, verificatieService())

        val antwoord = TestTenants.metTenant(zonderVerificatie) { mutatie.createUserDigitaleAdres(authenticatie, verzoek) }

        assertThat(antwoord?.verificatieNeeded).isFalse()
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een tenant zonder verificatie werkt een digitaal adres bij en verwijdert het`() {
        runBlocking { whenever(openklant.updateDigitaleAdresById(any(), any())).thenReturn(adres) }
        val mutatie = DigitaleAdresMutation(openklant, verificatieService())

        val bijgewerkt = TestTenants.metTenant(zonderVerificatie) { mutatie.updateUserDigitaleAdres(authenticatie, verzoek) }
        val verwijderd = TestTenants.metTenant(zonderVerificatie) { mutatie.deleteUserDigitaleAdres(authenticatie, adres.uuid!!) }

        assertThat(bijgewerkt?.waarde).isEqualTo("a@b.example")
        assertThat(verwijderd).isNull()
        assertThat(bron.requestCount).isZero()
    }

    @Test
    fun `een tenant met verificatie voor e-mail start de verificatiestroom bij het opslaan`() {
        bron.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true}"""))
        val mutatie = DigitaleAdresMutation(openklant, verificatieService())

        val antwoord = TestTenants.metTenant(metVerificatie) { mutatie.createUserDigitaleAdres(authenticatie, verzoek) }

        assertThat(antwoord?.verificatieNeeded).isTrue()
        assertThat(bron.requestCount).isEqualTo(1)
    }
}