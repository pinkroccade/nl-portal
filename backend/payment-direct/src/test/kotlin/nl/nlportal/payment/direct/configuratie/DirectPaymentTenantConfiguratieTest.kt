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
package nl.nlportal.payment.direct.configuratie

import nl.nlportal.core.tenancy.TenantFileLoader
import nl.nlportal.core.tenancy.TenantFileResult
import nl.nlportal.core.tenancy.TenantFileViolation.Code
import nl.nlportal.core.tenancy.TenantModules
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

/** De bijdrage van payment-direct aan het tenantbestand: `modules.payment-direct` en de koppeling die het profiel noemt. */
class DirectPaymentTenantConfiguratieTest {
    @TempDir
    lateinit var dir: Path

    private val oin = "00000099000000000001"
    private val modules = TenantModules.van(listOf(DirectPaymentTenantConfiguratie()))

    private val koppelingen =
        """
        koppelingen:
          payment-direct:
            url: http://worldline.example.nl
            auth:
              type: gcs-v1-hmac
              clientId: api-sleutel
              secret: $oin.api-geheim
          parkeren:
            url: http://worldline.example.nl
            auth:
              type: gcs-v1-hmac
              clientId: parkeren-sleutel
              secret: $oin.parkeren-geheim
        """.trimIndent()

    private fun laad(modulesBlok: String): TenantFileResult {
        Files.writeString(dir.resolve("base.yaml"), "omgevingstype: test\ninlogmiddelen: [test]\n")
        Files.writeString(
            dir.resolve("$oin.yaml"),
            "oin: \"$oin\"\nnaam: Gemeente\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n$koppelingen\nmodules:\n  payment-direct:\n${modulesBlok.prependIndent("    ")}\n",
        )
        return TenantFileLoader(modules = modules).load(dir)
    }

    private fun overtredingen(result: TenantFileResult) = result.violations.map { it.pad to it.bericht }

    @Test
    fun `een geldige configuratie wordt getypeerd gelezen, met standaardwaarden voor de rest`() {
        val resultaat =
            laad(
                """
                webhookUrl: https://bosrand.example/api/public/payment/direct/postsale
                profielen:
                  belastingzaken:
                    pspId: TAX
                    returnUrl: https://bosrand.example/terug
                    webhookSleutelId: webhook-sleutel
                    webhookGeheim: $oin.webhook-geheim
                  parkeren:
                    pspId: PARK
                    returnUrl: https://bosrand.example/terug
                    koppeling: parkeren
                    creditcardAan: true
                    aantalPogingen: 3
                """.trimIndent(),
            )

        val configuratie = resultaat.orThrow().single().module<DirectPaymentConfiguratie>()!!

        assertThat(resultaat.violations).isEmpty()
        assertThat(configuratie.toonResultaatPagina).isFalse()
        assertThat(configuratie.profiel("PARK")).isEqualTo(configuratie.profielen["parkeren"])
        assertThat(configuratie.profielen.getValue("belastingzaken").taal).isEqualTo("nl_NL")
        assertThat(configuratie.profielen.getValue("belastingzaken").koppelingNaam()).isEqualTo("payment-direct")
        assertThat(configuratie.profielen.getValue("parkeren").koppelingNaam()).isEqualTo("parkeren")
        assertThat(configuratie.profiel("onbekend")).isNull()
    }

    @Test
    fun `een tenantbestand zonder profielen wordt geweigerd`() {
        assertThat(overtredingen(laad("profielen: {}"))).containsExactly("modules.payment-direct.profielen" to "Tenant $oin: minstens een profiel")
    }

    @Test
    fun `een profiel dat een onbekende koppeling noemt wordt geweigerd`() {
        val resultaat =
            laad(
                """
                profielen:
                  parkeren:
                    pspId: PARK
                    returnUrl: https://bosrand.example/terug
                    koppeling: bestaat-niet
                """.trimIndent(),
            )

        assertThat(overtredingen(resultaat)).containsExactly("modules.payment-direct.profielen.parkeren.koppeling" to "Tenant $oin: de koppeling staat niet onder koppelingen")
    }

    @Test
    fun `een webhooksleutel-id zonder geheim, of een geheim zonder sleutel-id, wordt geweigerd`() {
        val resultaat =
            laad(
                """
                profielen:
                  a:
                    pspId: A
                    returnUrl: https://bosrand.example/terug
                    webhookSleutelId: alleen-id
                  b:
                    pspId: B
                    returnUrl: https://bosrand.example/terug
                    webhookGeheim: $oin.webhook-geheim
                """.trimIndent(),
            )

        assertThat(overtredingen(resultaat).map { it.first }).containsExactlyInAnyOrder("modules.payment-direct.profielen.a", "modules.payment-direct.profielen.b")
        assertThat(overtredingen(resultaat).map { it.second }).allMatch { it.endsWith("webhookSleutelId en webhookGeheim horen samen") }
    }

    @Test
    fun `hetzelfde webhooksleutel-id bij twee profielen van een tenant wordt geweigerd`() {
        val resultaat =
            laad(
                """
                profielen:
                  a:
                    pspId: A
                    returnUrl: https://bosrand.example/terug
                    webhookSleutelId: dubbel
                    webhookGeheim: $oin.webhook-geheim
                  b:
                    pspId: B
                    returnUrl: https://bosrand.example/terug
                    webhookSleutelId: dubbel
                    webhookGeheim: $oin.ander-geheim
                """.trimIndent(),
            )

        assertThat(overtredingen(resultaat)).containsExactly("modules.payment-direct.profielen.b.webhookSleutelId" to "Tenant $oin: hetzelfde sleutel-ID bij meer profielen")
    }

    @Test
    fun `minder dan een betaalpoging wordt geweigerd`() {
        val resultaat =
            laad(
                """
                profielen:
                  a:
                    pspId: A
                    returnUrl: https://bosrand.example/terug
                    aantalPogingen: 0
                """.trimIndent(),
            )

        assertThat(overtredingen(resultaat)).containsExactly("modules.payment-direct.profielen.a.aantalPogingen" to "Tenant $oin: minstens 1")
    }

    @Test
    fun `een webhookgeheim van een andere tenant is niet uit te drukken`() {
        val resultaat =
            laad(
                """
                profielen:
                  a:
                    pspId: A
                    returnUrl: https://bosrand.example/terug
                    webhookSleutelId: sleutel
                    webhookGeheim: 00000099000000000002.webhook-geheim
                """.trimIndent(),
            )

        assertThat(resultaat.violations.map { it.code }).containsExactly(Code.GEHEIM_VAN_ANDERE_TENANT)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "webhookUrl: https://basis.example/postsale",
            "customTemplateUrl: sjabloon.html",
            "toonResultaatPagina: true",
            "profielen:\n      a:\n        webhookSleutelId: uit-de-basis",
            "profielen:\n      a:\n        returnUrl: https://basis.example/terug",
        ],
    )
    fun `instellingen per gemeente komen nooit uit de gedeelde basis`(basisWaarde: String) {
        Files.writeString(dir.resolve("base.yaml"), "omgevingstype: test\ninlogmiddelen: [test]\nmodules:\n  payment-direct:\n    $basisWaarde\n")
        Files.writeString(dir.resolve("$oin.yaml"), "oin: \"$oin\"\nnaam: Gemeente\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n")

        val resultaat = TenantFileLoader(modules = modules).load(dir)

        assertThat(resultaat.violations.map { it.code }).contains(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
    }
}