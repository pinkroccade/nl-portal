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
package nl.nlportal.openklant.graphql.domain

import java.util.UUID
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.openklant.client.domain.OpenKlant2DigitaleAdres
import nl.nlportal.verificatie.graphql.domain.VerificatieType
import nl.nlportal.verificatie.service.VerificatieService
import nl.nlportal.verificatie.tenant.VerificatieConfiguratie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Verificatie is per tenant: een tenant zonder `modules.verificatie` leest zijn digitale adressen zonder fout en zonder verificatie. */
internal class DigitaleAdresResponseTenantTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD
    private val zonderVerificatie = TestTenants.tenant(bosrand)
    private val metVerificatie =
        TestTenants.tenant(wijdeVeld, modules = mapOf("verificatie" to VerificatieConfiguratie(typenMetVerificatie = listOf(VerificatieType.EMAIL))))
    private val service =
        VerificatieService(TestTenants.register(zonderVerificatie, metVerificatie), TestTenants.directory(zonderVerificatie, metVerificatie), TestTenants.ALLE_GEHEIMEN)
    private val adres = OpenKlant2DigitaleAdres(uuid = UUID.randomUUID(), adres = "a@b.example", omschrijving = "werk", soortDigitaalAdres = "email")

    @Test
    fun `een tenant zonder verificatie leest een digitaal adres zonder fout en zonder verificatie nodig`() {
        val antwoord = TestTenants.metTenant(bosrand) { DigitaleAdresResponse.fromOpenKlant2DigitaleAdres(adres, service) }

        assertThat(antwoord.verificatieNeeded).isFalse()
        assertThat(antwoord.waarde).isEqualTo("a@b.example")
    }

    @Test
    fun `een tenant met verificatie voor e-mail vereist verificatie van een onbevestigd adres`() {
        val antwoord = TestTenants.metTenant(wijdeVeld) { DigitaleAdresResponse.fromOpenKlant2DigitaleAdres(adres, service) }

        assertThat(antwoord.verificatieNeeded).isTrue()
    }
}