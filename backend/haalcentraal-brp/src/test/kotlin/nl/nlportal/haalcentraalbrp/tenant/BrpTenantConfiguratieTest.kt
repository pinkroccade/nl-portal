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
package nl.nlportal.haalcentraalbrp.tenant

import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.TenantFileLoader
import nl.nlportal.core.tenancy.TenantFileViolation.Code
import nl.nlportal.core.tenancy.TenantModules
import nl.nlportal.core.testing.ModuleGeheimenControle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Seam: wat het tenantbestand voor `modules.brp` toestaat en weigert, door de echte loader en de ServiceLoader-registratie. */
internal class BrpTenantConfiguratieTest {
    @TempDir
    lateinit var dir: Path

    private val oin = "00000099000000000001"
    private val spi = BrpTenantConfiguratie()
    private val metBrpKoppeling = ModuleContext(setOf("brp"))

    @Test
    fun `de module wordt ontdekt via de ServiceLoader onder modules brp`() {
        val modules = TenantModules.ontdek()

        assertThat(modules.configuratie("brp")?.klasse).isEqualTo(BrpConfiguratie::class)
    }

    @Test
    fun `de configuratie bevat geen geheim als gewone tekst`() {
        assertThat(ModuleGeheimenControle.verdachteVelden(TenantModules.van(listOf(spi)))).isEmpty()
    }

    @Test
    fun `een lege configuratie krijgt de standaardvelden en laat alle woonplaatsen toe`() {
        val configuratie = laad(modulesBlok = "modules:\n  brp: {}\n").tenants.single().module<BrpConfiguratie>()

        assertThat(configuratie?.velden).isEqualTo(BrpConfiguratie.STANDAARD_VELDEN).contains("burgerservicenummer")
        assertThat(configuratie?.toegestaneWoonplaatsenBewoning).isEmpty()
        assertThat(configuratie?.aanvullendeHeaders).isEmpty()
    }

    @Test
    fun `de configuratie van de tenant wordt getypeerd gelezen`() {
        val resultaat =
            laad(
                modulesBlok =
                    """
                    modules:
                      brp:
                        velden: [naam, leeftijd]
                        toegestaneWoonplaatsenBewoning: ["'s-Gravenhage", Ypenburg]
                        aanvullendeHeaders:
                          x-doelbinding: bosrand
                    """.trimIndent(),
            )

        assertThat(resultaat.violations).isEmpty()
        val configuratie = resultaat.tenants.single().module<BrpConfiguratie>()
        assertThat(configuratie?.velden).containsExactly("naam", "leeftijd")
        assertThat(configuratie?.toegestaneWoonplaatsenBewoning).containsExactly("'s-Gravenhage", "Ypenburg")
        assertThat(configuratie?.aanvullendeHeaders).containsEntry("x-doelbinding", "bosrand")
    }

    @Test
    fun `een tenant met modules brp zonder koppeling brp wordt geweigerd`() {
        val resultaat = laad(koppelingenBlok = "", modulesBlok = "modules:\n  brp: {}\n")

        assertThat(resultaat.violations.map { it.code }).containsExactly(Code.MODULE_CONFIGURATIE_ONGELDIG)
        assertThat(resultaat.violations.single().bericht).contains("koppeling 'brp' ontbreekt")
    }

    @Test
    fun `een onbekende eigenschap onder modules brp wordt geweigerd`() {
        val resultaat = laad(modulesBlok = "modules:\n  brp:\n    brpFields: [naam]\n")

        assertThat(resultaat.violations).isNotEmpty()
        assertThat(resultaat.tenants).isEmpty()
    }

    @Test
    fun `de koppeling brp mag niet zonder sleutel starten`() {
        val resultaat = laad(koppelingenBlok = "koppelingen:\n  brp:\n    url: https://brp.example.nl\n    auth:\n      type: api-key\n      headerNaam: X-API-KEY\n", modulesBlok = "modules:\n  brp: {}\n")

        assertThat(resultaat.violations.map { it.code }).contains(Code.KOPPELING_ONVOLLEDIG)
    }

    @Test
    fun `een lijst velden zonder velden wordt geweigerd`() {
        assertThat(spi.valideer(BrpConfiguratie(velden = emptyList()), metBrpKoppeling).map { it.pad }).containsExactly("velden")
    }

    @Test
    fun `een leeg veld of een lege woonplaats wordt geweigerd`() {
        val overtredingen = spi.valideer(BrpConfiguratie(velden = listOf("naam", " "), toegestaneWoonplaatsenBewoning = listOf("")), metBrpKoppeling)

        assertThat(overtredingen.map { it.pad }).containsExactlyInAnyOrder("velden", "toegestaneWoonplaatsenBewoning")
    }

    @Test
    fun `een ongeldige of verboden header wordt geweigerd zonder de waarde te noemen`() {
        val overtredingen =
            spi.valideer(
                BrpConfiguratie(aanvullendeHeaders = mapOf("geen spatie" to "waarde-1", "Authorization" to "waarde-2")),
                metBrpKoppeling,
            )

        assertThat(overtredingen).hasSize(2)
        assertThat(overtredingen.map { it.bericht }).noneMatch { it.contains("waarde-") || it.contains("geen spatie") }
    }

    @Test
    fun `een header met de naam van de auth-header van de koppeling wordt bij het opstarten geweigerd, in elke schrijfwijze`() {
        val context = ModuleContext(setOf("brp", "brp-bewoning"), mapOf("brp" to "X-API-KEY", "brp-bewoning" to "X-Bewoning-Sleutel"))

        val overtredingen = spi.valideer(BrpConfiguratie(aanvullendeHeaders = mapOf("x-api-key" to "a", "X-Bewoning-Sleutel" to "b", "x-doelbinding" to "c")), context)

        assertThat(overtredingen).hasSize(2)
        assertThat(overtredingen.map { it.pad }).containsOnly("aanvullendeHeaders")
        assertThat(overtredingen.map { it.bericht }).noneMatch { it.contains("x-api-key", ignoreCase = true) || it.contains("Sleutel") }
    }

    @Test
    fun `een aanvullende header met de auth-header van de koppeling weigert het opstarten via de echte loader`() {
        val resultaat =
            laad(
                modulesBlok = "modules:\n  brp:\n    aanvullendeHeaders:\n      X-API-KEY: iets\n",
                koppelingenBlok = "koppelingen:\n  brp:\n    url: https://brp.example.nl\n    auth:\n      type: api-key\n      headerNaam: X-API-KEY\n      waarde: $oin.brp-sleutel\n",
            )

        assertThat(resultaat.violations.map { it.code }).containsExactly(Code.MODULE_CONFIGURATIE_ONGELDIG)
    }

    @Test
    fun `een header die op een geheim lijkt hoort bij de koppeling en wordt geweigerd, ook zonder de naam of waarde te noemen`() {
        val geheimeNamen = listOf("X-Gemeente-Token", "x-api-key", "X-Secret", "wachtwoord", "X-Password", "X-Sleutel", "Authorization", "x-apikey")

        geheimeNamen.forEach { naam ->
            val overtredingen = spi.valideer(BrpConfiguratie(aanvullendeHeaders = mapOf(naam to "waarde-1")), metBrpKoppeling)

            assertThat(overtredingen).describedAs(naam).hasSize(1)
            assertThat(overtredingen.single().bericht).describedAs(naam).doesNotContain("waarde-1").doesNotContainIgnoringCase(naam)
        }
    }

    @Test
    fun `gewone doelbindingsheaders zijn geen geheim en blijven toegestaan`() {
        val headers = mapOf("x-origin-oin" to "1", "x-doelbinding" to "2", "x-verwerking" to "3", "x-gebruiker" to "4")

        assertThat(spi.valideer(BrpConfiguratie(aanvullendeHeaders = headers), metBrpKoppeling)).isEmpty()
    }

    @Test
    fun `aanvullende headers zijn van de tenant en komen nooit uit de gedeelde basis`() {
        val resultaat =
            laad(
                modulesBlok = "modules:\n  brp: {}\n",
                basis = "omgevingstype: acceptatie\ninlogmiddelen: [digid]\nmodules:\n  brp:\n    aanvullendeHeaders:\n      x-origin-oin: van-een-andere-gemeente\n",
            )

        assertThat(resultaat.violations.map { it.code }).contains(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
        assertThat(resultaat.violations.map { it.toString() }).noneMatch { it.contains("van-een-andere-gemeente") }
    }

    @Test
    fun `een geldige configuratie met koppeling heeft geen overtredingen`() {
        assertThat(spi.valideer(BrpConfiguratie(aanvullendeHeaders = mapOf("x-doelbinding" to "d")), metBrpKoppeling)).isEmpty()
    }

    private fun laad(
        koppelingenBlok: String = "koppelingen:\n  brp:\n    url: https://brp.example.nl\n    auth:\n      type: api-key\n      headerNaam: X-API-KEY\n      waarde: $oin.brp-sleutel\n",
        modulesBlok: String,
        basis: String = "omgevingstype: acceptatie\ninlogmiddelen: [digid]\n",
    ): nl.nlportal.core.tenancy.TenantFileResult {
        Files.writeString(dir.resolve("base.yaml"), basis)
        Files.writeString(
            dir.resolve("$oin.yaml"),
            "oin: \"$oin\"\nnaam: Gemeente De Bosrand\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n$koppelingenBlok$modulesBlok\n",
        )
        return TenantFileLoader(modules = TenantModules.van(listOf(spi))).load(dir)
    }
}