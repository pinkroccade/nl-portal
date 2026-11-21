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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

data class ProefGeheimItem(
    val sleutel: GeheimVerwijzing,
    val naam: String? = null,
)

/** Geheimverwijzingen in lijsten, verzamelingen en maps: ze moeten net zo goed per tenant zijn als een los veld. */
data class ProefGeheimen(
    val sleutels: List<GeheimVerwijzing> = emptyList(),
    val perType: Map<String, GeheimVerwijzing> = emptyMap(),
    val items: List<ProefGeheimItem> = emptyList(),
    val enkel: GeheimVerwijzing? = null,
)

class ProefGeheimenModule : TenantModuleConfiguratie<ProefGeheimen> {
    override val naam = "geheimen"
    override val klasse: KClass<ProefGeheimen> = ProefGeheimen::class
}

/**
 * Seam: de loader en de validator-CLI (zelfde code) weigeren elke geheimverwijzing en elk sleutelbestandspad buiten de
 * naamruimte van de eigen OIN (ADR 0021 besluit 4 punt 5), en kennen de overige regels voor koppelingen en modules.
 */
class TenantNaamruimteTest {
    @TempDir
    lateinit var dir: Path

    private val bosrand = "00000099000000000001"
    private val wijdeVeld = "00000099000000000002"
    private val modules = TenantModules.van(listOf(ProefModule(), ProefGeheimenModule()))
    private val basis = "omgevingstype: test\ninlogmiddelen: [test]\n"

    private fun schrijf(
        naam: String,
        inhoud: String,
    ) = Files.writeString(dir.resolve(naam), inhoud.trimIndent())

    private fun tenant(
        extra: String,
        oin: String = bosrand,
        omgeving: String = basis,
    ) = schrijf("$oin.yaml", "oin: \"$oin\"\nnaam: Gemeente\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n$omgeving$extra\n")

    private fun laad() = TenantFileLoader(modules = modules).load(dir)

    private fun koppeling(
        waarde: String,
        url: String = "https://zaken.example.nl",
    ) = "koppelingen:\n  zaken:\n    url: $url\n    auth:\n      type: api-key\n      headerNaam: X-Api-Key\n      waarde: $waarde\n"

    private fun geheimen(inhoud: String) = "modules:\n  geheimen:\n" + inhoud.trimIndent().prependIndent("    ") + "\n"

    // --- melding bij een waarde die het schema doorlaat maar Jackson weigert (review-r2 L9) ---

    @Test
    fun `een waarde met de verkeerde vorm in modules krijgt het voorvoegsel met de OIN`() {
        tenant("modules:\n  proef:\n    brpVelden: [naam]\n    timeout: geen-duur\n")

        val violation = laad().violations.single()

        assertThat(violation.code).isEqualTo(Code.ONGELDIGE_WAARDE)
        assertThat(violation.bericht).startsWith("Tenant $bosrand: ")
    }

    // --- H1: naamruimte per OIN ---

    @Test
    fun `een geheimverwijzing van de eigen OIN wordt geladen, met elk scheidingsteken`() {
        listOf("$bosrand.zaken-sleutel", "$bosrand-zaken", "${bosrand}_zaken").forEach { naam ->
            tenant(koppeling(naam))

            assertThat(laad().violations).describedAs(naam).isEmpty()
        }
    }

    @Test
    fun `een geheimverwijzing zonder de OIN van het eigen bestand weigert het opstarten, zonder de naam te noemen`() {
        tenant(koppeling("bosrand-zaken-sleutel"))

        val result = laad()

        assertThat(result.violations.map { it.code }).containsExactly(Code.GEHEIM_VAN_ANDERE_TENANT)
        assertThat(result.violations.single().pad).isEqualTo("koppelingen.zaken.auth.waarde")
        assertThat(result.violations.single().bericht).startsWith("Tenant $bosrand:")
        assertThat(result.violations.single().toString()).doesNotContain("bosrand-zaken-sleutel")
    }

    @Test
    fun `een geheimverwijzing naar de naamruimte van een andere OIN weigert het opstarten`() {
        tenant(koppeling("$wijdeVeld.zaken-sleutel"))

        val result = laad()

        assertThat(result.violations.map { it.code }).containsExactly(Code.GEHEIM_VAN_ANDERE_TENANT)
        assertThat(result.violations.single().toString()).doesNotContain(wijdeVeld)
    }

    @Test
    fun `een OIN is een heel eerste segment, geen voorvoegsel van een langer getal`() {
        tenant(koppeling("${bosrand}5.zaken"))
        assertThat(laad().violations.map { it.code }).containsExactly(Code.GEHEIM_VAN_ANDERE_TENANT)

        tenant(koppeling("x$bosrand.zaken"))
        assertThat(laad().violations.map { it.code }).containsExactly(Code.GEHEIM_VAN_ANDERE_TENANT)
    }

    @Test
    fun `geheimverwijzingen in modulegegevens vallen ook onder de naamruimte, in velden, lijsten, maps en geneste items`() {
        tenant(
            geheimen(
                """
                enkel: $bosrand.goed
                sleutels: [$bosrand.een, anders-een]
                perType:
                  a: $bosrand.twee
                  b: $wijdeVeld.drie
                items:
                  - sleutel: $bosrand.vier
                  - sleutel: vijf
                """,
            ),
        )

        val result = laad()

        assertThat(result.violations.map { it.code }).containsOnly(Code.GEHEIM_VAN_ANDERE_TENANT)
        assertThat(result.violations.map { it.pad })
            .containsExactlyInAnyOrder("modules.geheimen.sleutels[1]", "modules.geheimen.perType.b", "modules.geheimen.items[1].sleutel")
        assertThat(result.violations).allSatisfy { assertThat(it.bericht).startsWith("Tenant $bosrand:") }
    }

    private fun privateKey(pad: String) = "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      type: oauth2-private-key-jwt\n      tokenUrl: https://idp.example.nl/token\n      clientId: client\n      privateKeyBestand: \"${pad.replace("\\", "\\\\")}\"\n"

    @Test
    fun `een sleutelbestand valt onder een map met de eigen OIN`() {
        listOf("/run/secrets/$bosrand/client.pem", "$bosrand/client.pem", "C:\\secrets\\$bosrand\\client.pem").forEach { pad ->
            tenant(privateKey(pad))

            assertThat(laad().violations).describedAs(pad).isEmpty()
        }
    }

    @Test
    fun `een sleutelbestand buiten de map van de eigen OIN weigert het opstarten`() {
        listOf(
            "/run/secrets/client.pem",
            "/run/secrets/$wijdeVeld/client.pem",
            "/run/secrets/$bosrand/../$wijdeVeld/client.pem",
            "../$bosrand/client.pem",
            "/run/secrets/$bosrand/$wijdeVeld/client.pem",
            "/run/secrets/${bosrand}5/client.pem",
            "/run/secrets/$bosrand",
        ).forEach { pad ->
            tenant(privateKey(pad))

            val result = laad()

            assertThat(result.violations.map { it.code }).describedAs(pad).containsExactly(Code.GEHEIM_VAN_ANDERE_TENANT)
            assertThat(result.violations.single().pad).isEqualTo("koppelingen.zaken.auth.privateKeyBestand")
            assertThat(result.violations.single().toString()).doesNotContain("client.pem")
        }
    }

    @Test
    fun `de validator-CLI weigert een verwijzing naar een andere OIN`() {
        tenant(koppeling("$wijdeVeld.zaken-sleutel"))
        val err = ByteArrayOutputStream()

        val code = TenantValidatorCli.run(listOf(dir.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(err))

        assertThat(code).isEqualTo(1)
        assertThat(err.toString()).contains("GEHEIM_VAN_ANDERE_TENANT")
    }

    // --- M2: niet-erfbaarheid ook voor lijsten en maps ---

    @Test
    fun `een geheimverwijzing in een lijst, set of map komt nooit uit de gedeelde basis`() {
        schrijf("base.yaml", basis + geheimen("sleutels: [gedeeld]\nperType:\n  a: gedeeld\nitems:\n  - sleutel: gedeeld\n  - naam: zonder"))
        tenant("")

        val result = laad()

        assertThat(result.violations.map { it.code }).containsOnly(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
        assertThat(result.violations.map { it.pad })
            .containsExactlyInAnyOrder("modules.geheimen.sleutels[]", "modules.geheimen.perType.a", "modules.geheimen.items[].sleutel")
        assertThat(result.violations).allSatisfy { assertThat(it.bestand).isEqualTo("base.yaml") }
    }

    // --- M4: https in productie ---

    private val productie = "omgevingstype: productie\ninlogmiddelen: [digid]\n"

    @Test
    fun `in productie zijn url en tokenUrl alleen https`() {
        tenant(koppeling("$bosrand.zaken", url = "http://zaken.example.nl"), omgeving = productie)
        val url = laad()
        tenant(
            "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      type: oauth2-client-credentials\n      tokenUrl: http://idp.example.nl/token\n      clientId: c\n      secret: $bosrand.s\n",
            omgeving = productie,
        )
        val tokenUrl = laad()

        assertThat(url.violations.map { it.code }).containsExactly(Code.ONGELDIGE_WAARDE)
        assertThat(url.violations.single().pad).isEqualTo("koppelingen.zaken.url")
        assertThat(url.violations.single().bericht).contains("https")
        assertThat(tokenUrl.violations.map { it.pad }).containsExactly("koppelingen.zaken.auth.tokenUrl")
    }

    @Test
    fun `buiten productie blijft http toegestaan en in productie https`() {
        tenant(koppeling("$bosrand.zaken", url = "http://zaken.example.nl"))
        assertThat(laad().violations).isEmpty()

        tenant(koppeling("$bosrand.zaken", url = "https://zaken.example.nl"), omgeving = productie)
        assertThat(laad().violations).isEmpty()
    }

    // --- L4: namen en headers ---

    @Test
    fun `een koppelingnaam volgt het patroon van een modulenaam`() {
        listOf("Zaken", "zaken.api", "zaken api", "1zaken").forEach { naam ->
            tenant(koppeling("$bosrand.zaken").replace("  zaken:", "  \"$naam\":"))

            val result = laad()

            assertThat(result.violations.map { it.code }).describedAs(naam).containsExactly(Code.ONGELDIGE_WAARDE)
            assertThat(result.violations.single().pad).isEqualTo("koppelingen.$naam")
        }
    }

    @Test
    fun `een headernaam is een HTTP-token en geen header die het transport bepaalt`() {
        listOf("Host", "cookie", "Content-Length", "Transfer-Encoding", "X Api Key", "X:Key").forEach { header ->
            tenant(koppeling("$bosrand.zaken").replace("X-Api-Key", "\"$header\""))

            val result = laad()

            assertThat(result.violations.map { it.code }).describedAs(header).containsExactly(Code.ONGELDIGE_WAARDE)
            assertThat(result.violations.single().pad).isEqualTo("koppelingen.zaken.auth.headerNaam")
        }
        tenant(koppeling("$bosrand.zaken").replace("X-Api-Key", "Authorization"))
        assertThat(laad().violations).isEmpty()
    }

    // --- L12: een module ziet de koppelingnamen van de tenant ---

    @Test
    fun `een module kan controleren dat een koppeling bestaat die zijn configuratie noemt`() {
        val module =
            object : TenantModuleConfiguratie<ProefConfiguratie> {
                override val naam = "proef"
                override val klasse = ProefConfiguratie::class

                override fun valideer(
                    configuratie: ProefConfiguratie,
                    context: ModuleContext,
                ) = if ("documenten" in context.koppelingen) emptyList() else listOf(ModuleOvertreding("brpVelden", "koppeling documenten ontbreekt"))
            }
        tenant("modules:\n  proef:\n    brpVelden: [naam]\n")
        val zonder = TenantFileLoader(modules = TenantModules.van(listOf(module))).load(dir)
        tenant(koppeling("$bosrand.zaken").replace("  zaken:", "  documenten:") + "modules:\n  proef:\n    brpVelden: [naam]\n")
        val met = TenantFileLoader(modules = TenantModules.van(listOf(module))).load(dir)

        assertThat(zonder.violations.map { it.code }).containsExactly(Code.MODULE_CONFIGURATIE_ONGELDIG)
        assertThat(zonder.violations.single().bericht).isEqualTo("Tenant $bosrand: koppeling documenten ontbreekt")
        assertThat(met.violations).isEmpty()
    }

    // --- review M2: een configuratieklasse hoort bij precies een module ---

    @Test
    fun `twee modules met dezelfde configuratieklasse kunnen niet samen`() {
        val tweede =
            object : TenantModuleConfiguratie<ProefConfiguratie> {
                override val naam = "tweede"
                override val klasse = ProefConfiguratie::class
            }

        val fout = assertThrows<IllegalStateException> { TenantModules.van(listOf(ProefModule(), tweede)) }

        assertThat(fout.message).contains("proef", "tweede")
    }

    @Test
    fun `een module vindt zijn configuratie op precies zijn klasse, niet op een supertype`() {
        val tenant = TenantDefinitionVoorTest.met(ProefConfiguratie(brpVelden = listOf("naam")))

        assertThat(tenant.module<ProefConfiguratie>()).isNotNull
        assertThat(tenant.module(Any::class)).isNull()
        assertThat(tenant.module(Comparable::class)).isNull()
    }
}

private object TenantDefinitionVoorTest {
    fun met(configuratie: Any) =
        TenantDefinition(
            oin = Oin("00000099000000000001"),
            naam = "N",
            omgevingstype = Omgevingstype.TEST,
            domeinen = listOf("a.example"),
            thema = Thema("T"),
            inlogmiddelen = setOf(Inlogmiddel.TEST),
            minimumNiveau = Betrouwbaarheidsniveau.BASIS,
            sessie = Sessie(),
            modules = mapOf("proef" to configuratie),
        )
}

/** Een gewone klasse (geen dataclass) met een geheimverwijzing: de geheimwandeling zou haar overslaan. */
class GewoneProfiel(
    val sleutel: GeheimVerwijzing,
)

data class ProefMetGewoneKlasse(
    val profiel: GewoneProfiel,
)

class GewoneKlasseModule : TenantModuleConfiguratie<ProefMetGewoneKlasse> {
    override val naam = "gewoon"
    override val klasse: KClass<ProefMetGewoneKlasse> = ProefMetGewoneKlasse::class
}

class GewoneConfiguratie(
    val sleutel: GeheimVerwijzing,
)

class GewoneConfiguratieModule : TenantModuleConfiguratie<GewoneConfiguratie> {
    override val naam = "gewoon"
    override val klasse: KClass<GewoneConfiguratie> = GewoneConfiguratie::class
}

/** Seam: een moduleconfiguratie die geen dataclass is, valt niet door het net van de geheimcontroles (review-r2 M1). */
class TenantModulesDataclassTest {
    @Test
    fun `een genest item dat geen dataclass is wordt bij het bouwen geweigerd`() {
        val fout = assertThrows<IllegalStateException> { TenantModules.van(listOf(GewoneKlasseModule())) }

        assertThat(fout.message).contains("GewoneProfiel").contains("geen dataclass")
    }

    @Test
    fun `een moduleconfiguratie die geen dataclass is wordt geweigerd`() {
        val fout = assertThrows<IllegalStateException> { TenantModules.van(listOf(GewoneConfiguratieModule())) }

        assertThat(fout.message).contains("GewoneConfiguratie").contains("geen dataclass")
    }
}