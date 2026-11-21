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
package nl.nlportal.core.tenancy

import nl.nlportal.core.tenancy.TenantFileViolation.Code
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Seam: het tenantbestand zoals een beheerder het aanlevert (map met `base.yaml` en `<oin>.yaml`) wordt tenants of een weigering. */
class TenantFileLoaderTest {
    @TempDir
    lateinit var dir: Path

    private val bosrand = "00000099000000000001"
    private val wijdeVeld = "00000099000000000002"

    private fun schrijf(
        naam: String,
        inhoud: String,
    ) = Files.writeString(dir.resolve(naam), inhoud.trimIndent())

    private val basis = "omgevingstype: acceptatie\ninlogmiddelen: [digid]"

    private fun tenant(
        oin: String = bosrand,
        domeinen: String = "[bosrand.example]",
        authenticatie: String = "authenticatie:\n  issuer: https://login.example/realms/$oin\n  sleutelBestand: /run/secrets/$oin/k.jwk\n",
        extra: String = "",
    ) = "oin: \"$oin\"\nnaam: Gemeente De Bosrand\ndomeinen: $domeinen\nthema:\n  titel: De Bosrand\n$authenticatie$extra\n"

    private fun laad(meerdere: Boolean = false) = TenantFileLoader(meerdereTenantsToegestaan = meerdere).load(dir)

    private fun codes(result: TenantFileResult) = result.violations.map { it.code }

    @Test
    fun `een geldig tenantbestand met gedeelde basis levert een tenant met de standaardwaarden`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant())

        val result = laad()

        assertThat(result.violations).isEmpty()
        val tenant = result.tenants.single()
        assertThat(tenant.oin).isEqualTo(Oin(bosrand))
        assertThat(tenant.domeinen).containsExactly("bosrand.example")
        assertThat(tenant.omgevingstype).isEqualTo(Omgevingstype.ACCEPTATIE)
        assertThat(tenant.inlogmiddelen).containsExactly(Inlogmiddel.DIGID)
        assertThat(tenant.minimumNiveau).isEqualTo(Betrouwbaarheidsniveau.SUBSTANTIEEL)
        assertThat(tenant.sessie.inactiviteit).isEqualTo(Duration.ofMinutes(15))
        assertThat(tenant.sessie.absoluut).isEqualTo(Duration.ofHours(8))
    }

    @Test
    fun `het tenantbestand overschrijft de gedeelde basis`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "inlogmiddelen: [wallet]\nminimumNiveau: hoog\nsessie:\n  inactiviteit: PT10M"))

        val tenant = laad().tenants.single()

        assertThat(tenant.inlogmiddelen).containsExactly(Inlogmiddel.WALLET)
        assertThat(tenant.minimumNiveau).isEqualTo(Betrouwbaarheidsniveau.HOOG)
        assertThat(tenant.sessie.inactiviteit).isEqualTo(Duration.ofMinutes(10))
        assertThat(tenant.sessie.absoluut).isEqualTo(Duration.ofHours(8))
    }

    @Test
    fun `een dubbel domein weigert het opstarten`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(domeinen = "[gedeeld.example]"))
        schrijf("$wijdeVeld.yaml", tenant(oin = wijdeVeld, domeinen = "[gedeeld.example]"))

        assertThat(codes(laad(meerdere = true))).containsExactly(Code.DUBBEL_DOMEIN)
    }

    @Test
    fun `een ongequote OIN weigert het opstarten`() {
        schrijf("base.yaml", basis)
        // YAML leest dit ongequote als octaal getal (alleen cijfers 0-7) of als geheel getal: de voorloopnullen zijn dan weg.
        for (ongequote in listOf("00000012345670000001", "12345678901234567890")) {
            schrijf("$ongequote.yaml", tenant(oin = ongequote).replace("\"$ongequote\"", ongequote))
            assertThat(codes(laad())).describedAs(ongequote).containsExactly(Code.OIN_NIET_GEQUOTE)
            Files.delete(dir.resolve("$ongequote.yaml"))
        }
    }

    @Test
    fun `een ontbrekende verplichte waarde weigert het opstarten`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant().replace("naam: Gemeente De Bosrand", ""))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.VERPLICHTE_WAARDE_ONTBREEKT)
        assertThat(result.violations.single().pad).isEqualTo("naam")
    }

    @Test
    fun `een omgevingstype dat nergens staat is een ontbrekende verplichte waarde`() {
        schrijf("$bosrand.yaml", tenant(extra = "inlogmiddelen: [digid]"))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.VERPLICHTE_WAARDE_ONTBREEKT)
        assertThat(result.violations.single().pad).isEqualTo("omgevingstype")
    }

    @Test
    fun `een leeg domeinen of inlogmiddelen is een weigering`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(domeinen = "[]", extra = "inlogmiddelen: []"))

        val result = laad()

        assertThat(codes(result)).containsOnly(Code.ONGELDIGE_WAARDE)
        assertThat(result.violations.map { it.pad }).containsExactlyInAnyOrder("domeinen", "inlogmiddelen")
    }

    @Test
    fun `een testinlogmiddel in een productieomgeving weigert het opstarten`() {
        schrijf("base.yaml", "omgevingstype: productie\ninlogmiddelen: [digid, test]")
        schrijf("$bosrand.yaml", tenant())

        assertThat(codes(laad())).containsExactly(Code.TESTINLOGMIDDEL_IN_PRODUCTIE)
    }

    @Test
    fun `een minimumniveau onder Substantieel in een productieomgeving weigert het opstarten`() {
        for (niveau in listOf("basis", "midden")) {
            schrijf("base.yaml", "omgevingstype: productie\ninlogmiddelen: [digid]")
            schrijf("$bosrand.yaml", tenant(extra = "minimumNiveau: $niveau"))

            val result = laad()

            assertThat(codes(result)).describedAs(niveau).containsExactly(Code.MINIMUMNIVEAU_TE_LAAG_IN_PRODUCTIE)
            assertThat(result.violations.single().pad).isEqualTo("minimumNiveau")
        }
    }

    @Test
    fun `een minimumniveau Substantieel of Hoog in productie, of Basis buiten productie, mag`() {
        for ((omgeving, niveau) in listOf("productie" to "substantieel", "productie" to "hoog", "acceptatie" to "basis", "test" to "midden")) {
            schrijf("base.yaml", "omgevingstype: $omgeving\ninlogmiddelen: [digid]")
            schrijf("$bosrand.yaml", tenant(extra = "minimumNiveau: $niveau"))

            assertThat(laad().violations).describedAs("$omgeving $niveau").isEmpty()
        }
    }

    @Test
    fun `twee basisbestanden naast elkaar weigeren het opstarten in plaats van er een stil te negeren`() {
        schrijf("base.yaml", basis)
        schrijf("base.yml", basis)
        schrijf("$bosrand.yaml", tenant())

        assertThat(codes(laad())).containsExactly(Code.MEERDERE_BASISBESTANDEN)
    }

    @Test
    fun `een testinlogmiddel in een ontwikkelomgeving mag`() {
        schrijf("base.yaml", "omgevingstype: ontwikkeling\ninlogmiddelen: [test]")
        schrijf("$bosrand.yaml", tenant())

        assertThat(laad().violations).isEmpty()
    }

    @Test
    fun `een onbekende eigenschap weigert het opstarten zonder de waarde te tonen`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "clientSecret: geheimeWaarde"))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.ONBEKENDE_EIGENSCHAP)
        assertThat(result.violations.single().pad).isEqualTo("clientSecret")
        assertThat(result.violations.single().toString()).doesNotContain("geheimeWaarde")
    }

    @Test
    fun `een tweede bestand met dezelfde OIN weigert het opstarten`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant())
        schrijf("kopie.yaml", tenant(domeinen = "[ander.example]"))

        assertThat(codes(laad(meerdere = true))).contains(Code.DUBBELE_OIN, Code.BESTANDSNAAM_KLOPT_NIET_MET_OIN)
    }

    @Test
    fun `de bestandsnaam moet de OIN zijn`() {
        schrijf("base.yaml", basis)
        schrijf("$wijdeVeld.yaml", tenant())

        assertThat(codes(laad())).containsExactly(Code.BESTANDSNAAM_KLOPT_NIET_MET_OIN)
    }

    @Test
    fun `een domein moet een genormaliseerde hostnaam zijn`() {
        schrijf("base.yaml", basis)
        for (slecht in listOf("Bosrand.Example", "bosrand.example.", "bosrand.example:8443", "192.168.1.1", "bos rand.example", "https://bosrand.example")) {
            schrijf("$bosrand.yaml", tenant(domeinen = "[\"$slecht\"]"))
            assertThat(codes(laad())).describedAs(slecht).containsExactly(Code.ONGELDIG_DOMEIN)
        }
    }

    @Test
    fun `een sessietijd buiten de grenzen van ADR 0022 weigert het opstarten`() {
        schrijf("base.yaml", basis)
        for (
        extra in
        listOf(
            "sessie:\n  inactiviteit: PT16M",
            "sessie:\n  absoluut: PT9H",
            "sessie:\n  inactiviteit: PT0S",
            "sessie:\n  inactiviteit: PT30M\n  absoluut: PT20M",
        )
        ) {
            schrijf("$bosrand.yaml", tenant(extra = extra))
            assertThat(codes(laad())).describedAs(extra).containsExactly(Code.SESSIETIJD_BUITEN_GRENZEN)
        }
    }

    @Test
    fun `zolang modules niet gemigreerd zijn weigert de validator een tweede tenant`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant())
        schrijf("$wijdeVeld.yaml", tenant(oin = wijdeVeld, domeinen = "[wijdeveld.example]"))

        assertThat(codes(laad())).containsExactly(Code.TWEEDE_TENANT_NOG_NIET_TOEGESTAAN)
        assertThat(laad(meerdere = true).violations).isEmpty()
    }

    @Test
    fun `een map zonder tenantbestanden of een ontbrekende map weigert het opstarten`() {
        schrijf("base.yaml", basis)

        assertThat(codes(laad())).containsExactly(Code.GEEN_TENANTBESTANDEN)
        assertThat(codes(TenantFileLoader().load(dir.resolve("bestaat-niet")))).containsExactly(Code.GEEN_TENANTBESTANDEN)
    }

    @Test
    fun `een bestand dat geen geldige YAML is wordt geweigerd`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", "oin: [ongesloten")

        assertThat(codes(laad())).containsExactly(Code.ONLEESBAAR)
    }

    @Test
    fun `de client-registratie is verplicht in het tenantbestand`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(authenticatie = ""))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.VERPLICHTE_WAARDE_ONTBREEKT)
        assertThat(result.violations.single().pad).isEqualTo("authenticatie")
    }

    @Test
    fun `een issuer die geen veilige absolute URL is weigert het opstarten`() {
        schrijf("base.yaml", basis)
        for (slecht in listOf("login.example/realms/x", "http://login.example/realms/x", "https://user@login.example/realms/x", "https://login.example/realms/x?a=1", "https://login.example/realms/x#f", "https://login.example/realms/x/", "ftp://login.example/x", "https://login example/x", "https:///realms/x")) {
            schrijf("$bosrand.yaml", tenant(authenticatie = "authenticatie:\n  issuer: \"$slecht\"\n  sleutelBestand: /run/secrets/$bosrand/k.jwk\n"))
            val result = laad()
            assertThat(codes(result)).describedAs(slecht).containsExactly(Code.ONGELDIGE_WAARDE)
            assertThat(result.violations.single().pad).isEqualTo("authenticatie.issuer")
        }
    }

    @Test
    fun `http voor de issuer mag alleen in een ontwikkelomgeving`() {
        schrijf("base.yaml", "omgevingstype: ontwikkeling\ninlogmiddelen: [test]")
        schrijf("$bosrand.yaml", tenant(authenticatie = "authenticatie:\n  issuer: http://keycloak.localhost:8082/realms/x\n  sleutelBestand: /run/secrets/$bosrand/k.jwk\n  interneBasisUrl: http://keycloak:8080/realms/x\n"))

        assertThat(laad().violations).isEmpty()
    }

    @Test
    fun `een onbruikbare interne basis-URL weigert het opstarten`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(authenticatie = "authenticatie:\n  issuer: https://login.example/realms/x\n  sleutelBestand: /run/secrets/$bosrand/k.jwk\n  interneBasisUrl: keycloak\n"))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.ONGELDIGE_WAARDE)
        assertThat(result.violations.single().pad).isEqualTo("authenticatie.interneBasisUrl")
    }

    @Test
    fun `twee tenants met dezelfde issuer weigeren het opstarten, en een sleutelbestand van een andere OIN ook`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant())
        schrijf(
            "$wijdeVeld.yaml",
            tenant(
                oin = wijdeVeld,
                domeinen = "[wijdeveld.example]",
                authenticatie = listOf("authenticatie:", "  issuer: https://login.example/realms/$bosrand", "  sleutelBestand: /run/secrets/$wijdeVeld/k.jwk", "").joinToString("\n"),
            ),
        )

        assertThat(codes(laad(meerdere = true))).containsExactly(Code.DUBBELE_ISSUER)

        schrijf(
            "$wijdeVeld.yaml",
            tenant(
                oin = wijdeVeld,
                domeinen = "[wijdeveld.example]",
                authenticatie = listOf("authenticatie:", "  issuer: https://login.example/realms/$wijdeVeld", "  sleutelBestand: /run/secrets/$bosrand/k.jwk", "").joinToString("\n"),
            ),
        )

        assertThat(codes(laad(meerdere = true))).containsExactly(Code.GEHEIM_VAN_ANDERE_TENANT)
    }

    @Test
    fun `de client-registratie komt nooit uit de gedeelde basis`() {
        schrijf("base.yaml", "$basis\nauthenticatie:\n  issuer: https://login.example/realms/x\n  sleutelBestand: /k.jwk\n")
        schrijf("$bosrand.yaml", tenant())

        val result = laad()

        assertThat(codes(result)).containsExactlyInAnyOrder(Code.NIET_ERFBARE_WAARDE_IN_BASIS, Code.NIET_ERFBARE_WAARDE_IN_BASIS, Code.VERPLICHTE_WAARDE_ONTBREEKT, Code.VERPLICHTE_WAARDE_ONTBREEKT)
        assertThat(result.violations.filter { it.code == Code.NIET_ERFBARE_WAARDE_IN_BASIS }.map { it.pad }).containsExactlyInAnyOrder("authenticatie.issuer", "authenticatie.sleutelBestand")
    }

    @Test
    fun `een client-registratie in een tenantbestand krijgt de standaard client-id`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant())

        val tenant = laad().tenants.single()

        assertThat(tenant.authenticatie.clientId).isEqualTo("nl-portal-backend")
        assertThat(tenant.authenticatie.issuer).isEqualTo("https://login.example/realms/$bosrand")
        assertThat(tenant.authenticatie.interneBasisUrl).isNull()
    }

    @Test
    fun `een OIN van de verkeerde lengte is ongeldig`() {
        schrijf("base.yaml", basis)
        schrijf("12345.yaml", tenant(oin = "12345"))

        assertThat(codes(laad())).contains(Code.ONGELDIGE_WAARDE)
    }
}