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
package nl.nlportal.zakenapi.client

import nl.nlportal.core.tenancy.TenantFileLoader
import nl.nlportal.core.tenancy.TenantFileResult
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.tenancy.TenantModules
import nl.nlportal.core.testing.TestTenants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import nl.nlportal.core.tenancy.TenantModuleConfiguratie as Bijdrage

/** De bijdrage van Zaken API aan het tenantbestand (`modules.zakenapi`) en de opstartregels voor zijn koppeling. */
class ZakenApiTenantConfiguratieTest {
    @TempDir
    lateinit var dir: Path

    private val bosrand = "00000099000000000001"
    private val modules = TenantModules.van(listOf(ZakenApiTenantConfiguratie()))

    private fun laad(extra: String): TenantFileResult {
        Files.writeString(
            dir.resolve("$bosrand.yaml"),
            "oin: \"$bosrand\"\nnaam: Gemeente\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n" +
                "omgevingstype: test\ninlogmiddelen: [test]\n$extra\n",
        )
        return TenantFileLoader(modules = modules).load(dir)
    }

    @Test
    fun `de module is geregistreerd voor ServiceLoader, zodat schema en validator-CLI hem kennen`() {
        assertThat(ServiceLoader.load(Bijdrage::class.java).map { it.naam }).contains("zakenapi")
    }

    @Test
    fun `de modulegegevens worden getypeerd gelezen met standaardwaarden voor wat ontbreekt`() {
        val volledig = laad("modules:\n  zakenapi:\n    uitgeslotenZaaktypen: [c2d3e114-bbe4-478d-80de-e8a8c8f8a948]\n    nnpKvkQueryIdentificatoren: true\n")
        val leeg = laad("modules:\n  zakenapi: {}\n")

        assertThat(volledig.violations).isEmpty()
        assertThat(volledig.tenants.single().requireModule<ZakenApiConfiguratie>())
            .isEqualTo(ZakenApiConfiguratie(listOf("c2d3e114-bbe4-478d-80de-e8a8c8f8a948"), true))
        assertThat(leeg.tenants.single().requireModule<ZakenApiConfiguratie>()).isEqualTo(ZakenApiConfiguratie())
    }

    @Test
    fun `een uitgesloten zaaktype dat geen UUID is weigert het laden, zonder de waarde te noemen`() {
        val resultaat = laad("modules:\n  zakenapi:\n    uitgeslotenZaaktypen: [c2d3e114-bbe4-478d-80de-e8a8c8f8a948, geheime-waarde, 1-1-1-1-1]\n")

        assertThat(resultaat.violations).hasSize(2)
        assertThat(resultaat.violations.map { it.pad }).containsExactly("modules.zakenapi.uitgeslotenZaaktypen[1]", "modules.zakenapi.uitgeslotenZaaktypen[2]")
        assertThat(resultaat.violations.joinToString { it.bericht }).doesNotContain("geheime-waarde")
    }

    @Test
    fun `een onbekende eigenschap in de modulegegevens weigert het laden`() {
        val resultaat = laad("modules:\n  zakenapi:\n    url: https://oud.example.nl\n")

        assertThat(resultaat.violations).isNotEmpty
    }

    @Test
    fun `de uitgesloten zaaktypen komen als UUID uit de configuratie`() {
        val id = "c2d3e114-bbe4-478d-80de-e8a8c8f8a948"

        assertThat(ZakenApiConfiguratie(listOf(id)).uitgeslotenZaaktypeIds()).containsExactly(java.util.UUID.fromString(id))
    }

    @Test
    fun `een ZGW-geheim korter dan 32 tekens weigert het opstarten, zonder het geheim te noemen`() {
        val auth = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "nl-portal", secret = TestTenants.geheim("zaken-secret"))
        val tenant = TestTenants.tenant(koppelingen = mapOf("zakenapi" to TestTenants.koppeling("http://localhost:1", auth)))

        val fout = assertThrows<IllegalStateException> { TestTenants.register(tenant, geheimen = TestTenants.geheimen(TestTenants.geheim("zaken-secret") to "test-secret")) }

        assertThat(fout.message).startsWith("Tenant ${TestTenants.BOSRAND.value}: koppeling 'zakenapi': auth.secret:").doesNotContain("test-secret")
    }
}