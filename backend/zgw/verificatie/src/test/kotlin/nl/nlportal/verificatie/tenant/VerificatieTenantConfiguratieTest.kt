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
package nl.nlportal.verificatie.tenant

import java.nio.file.Files
import java.nio.file.Path
import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.TenantFileLoader
import nl.nlportal.core.tenancy.TenantFileResult
import nl.nlportal.core.tenancy.TenantFileViolation.Code
import nl.nlportal.core.tenancy.TenantModules
import nl.nlportal.verificatie.graphql.domain.VerificatieType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Seam: wat het tenantbestand voor `modules.verificatie` toestaat en weigert, door de echte loader en de ServiceLoader-registratie. */
internal class VerificatieTenantConfiguratieTest {
    @TempDir
    lateinit var dir: Path

    private val oin = "00000099000000000001"
    private val spi = VerificatieTenantConfiguratie()
    private val metKoppeling = ModuleContext(setOf("verificatie"))

    @Test
    fun `de module wordt ontdekt via de ServiceLoader onder modules verificatie`() {
        assertThat(TenantModules.ontdek().configuratie("verificatie")?.klasse).isEqualTo(VerificatieConfiguratie::class)
    }

    @Test
    fun `zonder koppeling verificatie is er een overtreding op de module zelf`() {
        val overtredingen = spi.valideer(VerificatieConfiguratie(), ModuleContext(emptySet()))

        assertThat(overtredingen.map { it.pad }).containsExactly("")
        assertThat(overtredingen.single().bericht).contains("koppeling 'verificatie' ontbreekt")
    }

    @Test
    fun `een leeg e-mailtemplate of sms-template wordt per veld geweigerd`() {
        val overtredingen = spi.valideer(VerificatieConfiguratie(templateIdEmail = " ", templateIdTelefoonnummer = ""), metKoppeling)

        assertThat(overtredingen.map { it.pad }).containsExactlyInAnyOrder("templateIdEmail", "templateIdTelefoonnummer")
    }

    @Test
    fun `een volledige configuratie met koppeling heeft geen overtredingen`() {
        val configuratie = VerificatieConfiguratie(templateIdEmail = "mail", templateIdTelefoonnummer = "sms", typenMetVerificatie = listOf(VerificatieType.EMAIL))

        assertThat(spi.valideer(configuratie, metKoppeling)).isEmpty()
    }

    @Test
    fun `de configuratie van de tenant wordt getypeerd gelezen`() {
        val resultaat = laad("modules:\n  verificatie:\n    templateIdEmail: mail\n    typenMetVerificatie: [EMAIL]\n    apiKey: $oin.verificatie-apikey\n")

        assertThat(resultaat.violations).isEmpty()
        val configuratie = resultaat.tenants.single().module<VerificatieConfiguratie>()
        assertThat(configuratie?.templateIdEmail).isEqualTo("mail")
        assertThat(configuratie?.typenMetVerificatie).containsExactly(VerificatieType.EMAIL)
    }

    @Test
    fun `templates en typen zijn van de tenant en komen nooit uit de gedeelde basis`() {
        val resultaat =
            laad(
                modulesBlok = "modules:\n  verificatie: {}\n",
                basis = "omgevingstype: acceptatie\ninlogmiddelen: [digid]\nmodules:\n  verificatie:\n    templateIdEmail: van-een-andere-gemeente\n    typenMetVerificatie: [EMAIL]\n",
            )

        assertThat(resultaat.violations.map { it.code }).contains(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
        assertThat(resultaat.violations.map { it.toString() }).noneMatch { it.contains("van-een-andere-gemeente") }
    }

    @Test
    fun `een apiKey uit de naamruimte van een andere gemeente wordt geweigerd`() {
        val resultaat = laad("modules:\n  verificatie:\n    apiKey: 00000099000000000002.verificatie-apikey\n")

        assertThat(resultaat.violations).isNotEmpty()
        assertThat(resultaat.tenants).isEmpty()
    }

    private fun laad(
        modulesBlok: String,
        basis: String = "omgevingstype: acceptatie\ninlogmiddelen: [digid]\n",
    ): TenantFileResult {
        Files.writeString(dir.resolve("base.yaml"), basis)
        Files.writeString(
            dir.resolve("$oin.yaml"),
            "oin: \"$oin\"\nnaam: Gemeente De Bosrand\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n" +
                "koppelingen:\n  verificatie:\n    url: https://verificatie.example\n    auth:\n      type: zgw-jwt\n      clientId: nl-portal\n      secret: $oin.verificatie-secret\n$modulesBlok\n",
        )
        return TenantFileLoader(modules = TenantModules.van(listOf(spi))).load(dir)
    }
}