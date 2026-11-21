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
package nl.nlportal.app.tenancy

import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.tenancy.TenantFileLoader
import nl.nlportal.payment.direct.configuratie.DirectPaymentConfiguratie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Seam: payment-direct met echte waarden in het tenantbestand, met alle modules van de app op het klassenpad. Een tikfout in het
 * voorbeeld of in de testtenant faalt hier, niet pas bij een gemeente.
 */
class PaymentDirectTenantAppTest {
    private val oin = "00000099000000000001"

    @Test
    fun `de testtenant van de app laadt payment-direct met een gcs-v1-hmac-koppeling`() {
        val resultaat = TenantFileLoader().load(Path.of("src/test/resources/tenants"))

        assertThat(resultaat.violations).isEmpty()
        val tenant = resultaat.tenants.single()
        val configuratie = tenant.module<DirectPaymentConfiguratie>()!!
        assertThat(configuratie.profiel("belastingzaken")!!.pspId).isEqualTo("TAX")
        assertThat(
            tenant.koppelingen
                .getValue("payment-direct")
                .auth.type,
        ).isEqualTo(TenantKoppelingAuthType.GCS_V1_HMAC)
    }

    @Test
    fun `het voorbeeld van payment-direct in het compose-tenantbestand voldoet na het weghalen van de commentaartekens aan de validator`(
        @TempDir map: Path,
    ) {
        val compose = Path.of("../../docker-compose/imports/tenants")
        Files.list(compose).use { bestanden -> bestanden.filter { Files.isRegularFile(it) }.forEach { Files.copy(it, map.resolve(it.fileName)) } }
        val tenantBestand = map.resolve("$oin.yaml")
        val regels = Files.readAllLines(tenantBestand)
        val begin = regels.indexOfFirst { it.startsWith("# koppelingen:") }
        assertThat(begin).describedAs("het voorbeeld staat onder '# koppelingen:'").isGreaterThan(0)
        Files.write(tenantBestand, regels.take(begin) + regels.drop(begin).map { it.removePrefix("# ").removePrefix("#") })

        val resultaat = TenantFileLoader().load(map)

        assertThat(resultaat.violations).isEmpty()
        assertThat(
            resultaat.tenants
                .single()
                .module<DirectPaymentConfiguratie>()!!
                .profielen,
        ).containsKey("belastingzaken")
    }
}