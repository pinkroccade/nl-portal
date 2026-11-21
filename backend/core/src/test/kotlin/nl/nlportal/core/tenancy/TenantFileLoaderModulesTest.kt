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
import nl.nlportal.core.util.ModuleNotConfiguredException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.reflect.KClass

enum class ProefNiveau { LAAG, HOOG }

data class ProefProfiel(
    val apiKey: GeheimVerwijzing,
    val naam: String? = null,
)

/** Een modulebijdrage met alle soorten velden die het schema moet kunnen beschrijven. */
data class ProefConfiguratie(
    val brpVelden: List<String>,
    val steden: Set<String> = emptySet(),
    val actief: Boolean = false,
    val maximum: Int = 10,
    val grens: Long = 1L,
    val niveau: ProefNiveau = ProefNiveau.LAAG,
    val timeout: Duration = Duration.ofSeconds(5),
    val profielen: Map<String, ProefProfiel> = emptyMap(),
    val geheim: GeheimVerwijzing? = null,
    @NietErfbaar val klantId: String? = null,
)

data class ProefItem(
    @NietErfbaar val sleutel: String? = null,
    val naam: String? = null,
)

data class ProefLijst(
    val items: List<ProefItem> = emptyList(),
)

data class ProefKommagetal(
    val bedrag: Double = 1.0,
)

data class ProefNummerMap(
    val waarden: Map<Int, String> = emptyMap(),
)

class ProefModule : TenantModuleConfiguratie<ProefConfiguratie> {
    override val naam = "proef"
    override val klasse: KClass<ProefConfiguratie> = ProefConfiguratie::class

    override fun valideer(configuratie: ProefConfiguratie) = if ("bsn" in configuratie.brpVelden) listOf(ModuleOvertreding("brpVelden", "bsn mag niet worden opgevraagd")) else emptyList()
}

/** Seam: de bijdrage van een module aan het tenantbestand (`modules.<naam>`) en `koppelingen` (epic #499, story 9, 11, 12, 14). */
class TenantFileLoaderModulesTest {
    @TempDir
    lateinit var dir: Path

    private val bosrand = "00000099000000000001"
    private val wijdeVeld = "00000099000000000002"
    private val modules = TenantModules.van(listOf(ProefModule()))

    private fun schrijf(
        naam: String,
        inhoud: String,
    ) = Files.writeString(dir.resolve(naam), inhoud.trimIndent())

    private fun tenant(
        oin: String = bosrand,
        extra: String = "",
        domein: String = "bosrand.example",
    ) = "oin: \"$oin\"\nnaam: Gemeente\ndomeinen: [$domein]\nthema:\n  titel: De Bosrand\nauthenticatie:\n  issuer: https://login.example/realms/$oin\n  sleutelBestand: /run/secrets/$oin.jwk\n$extra\n"

    private val basis = "omgevingstype: acceptatie\ninlogmiddelen: [digid]\n"

    /** Voor tests waarin de basis bewust ongeldig is: de tenant levert dan zelf de rest. */
    private val volledig = "omgevingstype: test\ninlogmiddelen: [test]\n"

    private fun laad(
        meerdere: Boolean = false,
        bijdragen: TenantModules = modules,
    ) = TenantFileLoader(meerdereTenantsToegestaan = meerdere, modules = bijdragen).load(dir)

    private fun codes(result: TenantFileResult) = result.violations.map { it.code }

    private val koppelingen = "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      type: api-key\n      headerNaam: X-Api-Key\n      waarde: $bosrand.zaken-sleutel\n"

    @Test
    fun `een module draagt zijn getypeerde configuratie bij en de tenant leest hem getypeerd`() {
        schrijf("base.yaml", basis)
        schrijf(
            "$bosrand.yaml",
            tenant(
                extra =
                    """
                    modules:
                      proef:
                        brpVelden: [naam, geboortedatum]
                        steden: [Utrecht, Zeist, Utrecht]
                        actief: true
                        maximum: 3
                        grens: 9000000000
                        niveau: HOOG
                        timeout: PT30S
                        geheim: $bosrand.proef-sleutel
                        klantId: klant-1
                        profielen:
                          standaard:
                            apiKey: $bosrand.proef-profiel-sleutel
                            naam: Standaard
                    """.trimIndent(),
            ),
        )

        val result = laad()

        assertThat(result.violations).isEmpty()
        val configuratie = result.tenants.single().requireModule<ProefConfiguratie>()
        assertThat(configuratie.brpVelden).containsExactly("naam", "geboortedatum")
        assertThat(configuratie.steden).containsExactlyInAnyOrder("Utrecht", "Zeist")
        assertThat(configuratie.actief).isTrue
        assertThat(configuratie.maximum).isEqualTo(3)
        assertThat(configuratie.grens).isEqualTo(9_000_000_000L)
        assertThat(configuratie.niveau).isEqualTo(ProefNiveau.HOOG)
        assertThat(configuratie.timeout).isEqualTo(Duration.ofSeconds(30))
        assertThat(configuratie.geheim).isEqualTo(GeheimVerwijzing("$bosrand.proef-sleutel"))
        assertThat(configuratie.klantId).isEqualTo("klant-1")
        assertThat(
            configuratie.profielen
                .getValue("standaard")
                .apiKey.naam,
        ).isEqualTo("$bosrand.proef-profiel-sleutel")
        assertThat(result.tenants.single().module<ProefConfiguratie>()).isSameAs(configuratie)
    }

    @Test
    fun `een tenant zonder modulegegevens heeft geen configuratie en vraagt erom is een fout`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant())

        val tenant = laad().tenants.single()

        assertThat(tenant.module<ProefConfiguratie>()).isNull()
        assertThat(tenant.modules).isEmpty()
        assertThrows<ModuleNotConfiguredException> { tenant.requireModule<ProefConfiguratie>() }
    }

    @Test
    fun `een module die niet op het klassenpad staat is een onbekende eigenschap`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  onbekend:\n    x: 1"))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.ONBEKENDE_EIGENSCHAP)
        assertThat(result.violations.single().pad).isEqualTo("modules.onbekend")
        assertThat(result.violations.single().bericht).startsWith("Tenant $bosrand")
    }

    @Test
    fun `zonder bijdragen is elke module onbekend`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    brpVelden: [naam]"))

        assertThat(codes(laad(bijdragen = TenantModules.GEEN))).containsExactly(Code.ONBEKENDE_EIGENSCHAP)
    }

    @Test
    fun `een verplichte moduleveld dat ontbreekt en een onbekend moduleveld weigeren het opstarten, met de OIN`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    maximum: 3\n    onzin: 1"))

        val result = laad()

        assertThat(codes(result)).containsExactlyInAnyOrder(Code.ONBEKENDE_EIGENSCHAP, Code.VERPLICHTE_WAARDE_ONTBREEKT)
        assertThat(result.violations.map { it.pad }).containsExactlyInAnyOrder("modules.proef.onzin", "modules.proef.brpVelden")
        assertThat(result.violations).allSatisfy { assertThat(it.bericht).startsWith("Tenant $bosrand") }
    }

    @Test
    fun `een moduleveld met de verkeerde vorm weigert het opstarten zonder de waarde te noemen`() {
        schrijf("base.yaml", basis)
        for ((veld, waarde) in listOf("actief" to "ja-hoor", "maximum" to "\"veel\"", "maximum" to "true", "niveau" to "extreem", "steden" to "Utrecht", "profielen" to "[a]")) {
            schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    brpVelden: [naam]\n    $veld: $waarde"))

            val result = laad()

            assertThat(codes(result)).describedAs("$veld: $waarde").containsExactly(Code.ONGELDIGE_WAARDE)
            assertThat(result.violations.single().bericht).doesNotContain("hoor", "veel", "extreem")
        }
    }

    @Test
    fun `een lijst die een tekst is weigert het opstarten`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    brpVelden: naam"))

        assertThat(codes(laad())).containsExactly(Code.ONGELDIGE_WAARDE)
    }

    @Test
    fun `een lege module weigert het opstarten`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n"))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.ONGELDIGE_WAARDE)
        assertThat(result.violations.single().pad).isEqualTo("modules.proef")
    }

    @Test
    fun `een waarde die de binding van de module niet slikt weigert het opstarten met het pad`() {
        schrijf("base.yaml", basis)
        // Een duur is voor het schema een tekst; pas bij het binden blijkt dat dit geen duur is.
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    brpVelden: [naam]\n    timeout: PT-onzin"))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.ONGELDIGE_WAARDE)
        assertThat(result.violations.single().pad).isEqualTo("modules.proef.timeout")
        assertThat(result.violations.single().bericht).doesNotContain("onzin")
    }

    @Test
    fun `de eigen regels van de module weigeren het opstarten, met het pad binnen de module en de OIN`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    brpVelden: [naam, bsn]"))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.MODULE_CONFIGURATIE_ONGELDIG)
        assertThat(result.violations.single().pad).isEqualTo("modules.proef.brpVelden")
        assertThat(result.violations.single().bericht).isEqualTo("Tenant $bosrand: bsn mag niet worden opgevraagd")
    }

    @Test
    fun `een overtreding zonder pad staat op de module zelf`() {
        val zonderPad =
            object : TenantModuleConfiguratie<ProefConfiguratie> {
                override val naam = "proef"
                override val klasse = ProefConfiguratie::class

                override fun valideer(configuratie: ProefConfiguratie) = listOf(ModuleOvertreding("", "niet compleet"))
            }
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    brpVelden: [naam]"))

        val result = laad(bijdragen = TenantModules.van(listOf(zonderPad)))

        assertThat(result.violations.single().pad).isEqualTo("modules.proef")
    }

    @Test
    fun `de gedeelde basis mag modulegegevens leveren die de tenant aanvult of overschrijft`() {
        schrijf("base.yaml", basis + "modules:\n  proef:\n    brpVelden: [naam]\n    maximum: 5\n")
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    maximum: 7"))
        schrijf("$wijdeVeld.yaml", tenant(oin = wijdeVeld, domein = "wijdeveld.example"))

        val result = laad(meerdere = true)

        assertThat(result.violations).isEmpty()
        val perOin = result.tenants.associateBy { it.oin.value }
        assertThat(perOin.getValue(bosrand).requireModule<ProefConfiguratie>().maximum).isEqualTo(7)
        assertThat(perOin.getValue(bosrand).requireModule<ProefConfiguratie>().brpVelden).containsExactly("naam")
        assertThat(perOin.getValue(wijdeVeld).requireModule<ProefConfiguratie>().maximum).isEqualTo(5)
    }

    @Test
    fun `credentials en geheimverwijzingen van een module komen nooit uit de gedeelde basis`() {
        schrijf("base.yaml", basis + "modules:\n  proef:\n    brpVelden: [naam]\n    geheim: gedeeld\n    klantId: gedeelde-klant\n    profielen:\n      p:\n        apiKey: gedeeld-profiel\n")
        schrijf("$bosrand.yaml", tenant(extra = volledig))

        val result = laad()

        assertThat(codes(result)).containsOnly(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
        assertThat(result.violations.map { it.pad }).containsExactlyInAnyOrder("modules.proef.geheim", "modules.proef.klantId", "modules.proef.profielen.p.apiKey")
        assertThat(result.violations).allSatisfy { assertThat(it.bestand).isEqualTo("base.yaml") }
    }

    @Test
    fun `de loader weigert een lijst in de basis waarvan een item een credential bevat`() {
        // Een niet-erfbaar veld in een lijst van objecten: de walk loopt ook door lijsten.
        val lijst =
            object : TenantModuleConfiguratie<ProefLijst> {
                override val naam = "lijst"
                override val klasse = ProefLijst::class
            }
        schrijf("base.yaml", basis + "modules:\n  lijst:\n    items:\n      - naam: een\n        sleutel: geheim\n")
        schrijf("$bosrand.yaml", tenant(extra = volledig))

        val result = laad(bijdragen = TenantModules.van(listOf(lijst)))

        assertThat(result.violations.single().code).isEqualTo(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
        assertThat(result.violations.single().pad).isEqualTo("modules.lijst.items[].sleutel")
    }

    @Test
    fun `een geheim in plaats van een verwijzing weigert het opstarten zonder het geheim te noemen`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "modules:\n  proef:\n    brpVelden: [naam]\n    geheim: \"Wachtwoord1!\""))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.GEHEIM_NIET_ALS_VERWIJZING)
        assertThat(result.violations.single().pad).isEqualTo("modules.proef.geheim")
        assertThat(result.violations.single().toString()).doesNotContain("Wachtwoord1")
    }

    // --- koppelingen ---

    @Test
    fun `een tenant leest zijn koppelingen met geheimen als verwijzing`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = koppelingen + "    leverancier: Open Zaak\n    versie: \"1.31\"\n"))

        val result = laad()

        assertThat(result.violations).isEmpty()
        val koppeling =
            result.tenants
                .single()
                .koppelingen
                .getValue("zaken")
        assertThat(koppeling.url).isEqualTo("https://zaken.example.nl")
        assertThat(koppeling.leverancier).isEqualTo("Open Zaak")
        assertThat(koppeling.versie).isEqualTo("1.31")
        assertThat(koppeling.auth.type).isEqualTo(TenantKoppelingAuthType.API_KEY)
        assertThat(koppeling.auth.waarde).isEqualTo(GeheimVerwijzing("$bosrand.zaken-sleutel"))
        assertThat(koppeling.auth.toString()).doesNotContain("zaken-sleutel")
    }

    @Test
    fun `een koppeling zonder url of auth type is onvolledig en weigert het opstarten, met de OIN`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = "koppelingen:\n  zaken:\n    auth:\n      headerNaam: X"))

        val result = laad()

        assertThat(codes(result)).containsExactlyInAnyOrder(Code.VERPLICHTE_WAARDE_ONTBREEKT, Code.VERPLICHTE_WAARDE_ONTBREEKT)
        assertThat(result.violations.map { it.pad }).containsExactlyInAnyOrder("koppelingen.zaken.url", "koppelingen.zaken.auth.type")
        assertThat(result.violations).allSatisfy { assertThat(it.bericht).startsWith("Tenant $bosrand") }
    }

    @Test
    fun `elke auth type vraagt om zijn eigen velden`() {
        schrijf("base.yaml", basis)
        val verwacht =
            mapOf(
                "api-key" to listOf("headerNaam", "waarde"),
                "bearer" to listOf("token"),
                "zgw-jwt" to listOf("clientId", "secret"),
                "oauth2-client-credentials" to listOf("tokenUrl", "clientId", "secret"),
                "oauth2-private-key-jwt" to listOf("tokenUrl", "clientId", "privateKeyBestand"),
            )
        verwacht.forEach { (type, velden) ->
            schrijf("$bosrand.yaml", tenant(extra = "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      type: $type"))

            val result = laad()

            assertThat(codes(result)).describedAs(type).containsOnly(Code.KOPPELING_ONVOLLEDIG)
            assertThat(result.violations.map { it.pad }).describedAs(type).containsExactlyInAnyOrderElementsOf(velden.map { "koppelingen.zaken.auth.$it" })
            assertThat(result.violations.first().bericht).isEqualTo("Tenant $bosrand: koppeling 'zaken' is onvolledig, $type vraagt om auth.${velden.first()}")
        }
    }

    @Test
    fun `een volledige koppeling per auth type wordt geladen`() {
        schrijf("base.yaml", basis)
        val auths =
            listOf(
                "type: bearer\n      token: $bosrand.bearer-token",
                "type: zgw-jwt\n      clientId: client\n      secret: $bosrand.zgw-secret",
                "type: oauth2-client-credentials\n      tokenUrl: https://idp.example.nl/token\n      clientId: client\n      secret: $bosrand.oauth-secret",
                "type: oauth2-private-key-jwt\n      tokenUrl: https://idp.example.nl/token\n      clientId: client\n      privateKeyBestand: /geheimen/$bosrand/sleutel.pem",
            )
        auths.forEach { auth ->
            schrijf("$bosrand.yaml", tenant(extra = "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      $auth"))

            assertThat(laad().violations).describedAs(auth).isEmpty()
        }
    }

    @Test
    fun `een ongeldige url of tokenUrl weigert het opstarten, ook met inloggegevens in de url`() {
        schrijf("base.yaml", basis)
        val ongeldig = listOf("geen url", "ftp://zaken.example.nl", "https://", "https://gebruiker:wachtwoord@zaken.example.nl", "/relatief")
        ongeldig.forEach { url ->
            schrijf("$bosrand.yaml", tenant(extra = "koppelingen:\n  zaken:\n    url: \"$url\"\n    auth:\n      type: bearer\n      token: $bosrand.t"))

            val result = laad()

            assertThat(codes(result)).describedAs(url).containsExactly(Code.ONGELDIGE_WAARDE)
            assertThat(result.violations.single().pad).isEqualTo("koppelingen.zaken.url")
            assertThat(result.violations.single().toString()).doesNotContain("wachtwoord")
        }
        schrijf(
            "$bosrand.yaml",
            tenant(extra = "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      type: oauth2-client-credentials\n      tokenUrl: nietuurl\n      clientId: c\n      secret: $bosrand.s"),
        )
        assertThat(laad().violations.map { it.pad }).containsExactly("koppelingen.zaken.auth.tokenUrl")
    }

    @Test
    fun `een secret als waarde in plaats van een verwijzing weigert het opstarten zonder het secret te noemen`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant(extra = koppelingen.replace("$bosrand.zaken-sleutel", "\"Dit Is Een Secret!\"")))

        val result = laad()

        assertThat(codes(result)).containsExactly(Code.GEHEIM_NIET_ALS_VERWIJZING)
        assertThat(result.violations.single().pad).isEqualTo("koppelingen.zaken.auth.waarde")
        assertThat(result.violations.single().toString()).doesNotContain("Dit Is")
    }

    @Test
    fun `de gedeelde basis levert de url, maar nooit de credentials van een koppeling`() {
        schrijf(
            "base.yaml",
            basis +
                "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      type: zgw-jwt\n      clientId: gedeelde-client\n      secret: gedeeld-secret\n      token: gedeeld-token\n      waarde: gedeelde-waarde\n      privateKeyBestand: /gedeeld.pem\n",
        )
        schrijf("$bosrand.yaml", tenant(extra = volledig))

        val result = laad()

        assertThat(codes(result)).containsOnly(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
        assertThat(result.violations.map { it.pad }).containsExactlyInAnyOrder(
            "koppelingen.zaken.auth.clientId",
            "koppelingen.zaken.auth.secret",
            "koppelingen.zaken.auth.token",
            "koppelingen.zaken.auth.waarde",
            "koppelingen.zaken.auth.privateKeyBestand",
        )
    }

    @Test
    fun `de tenant vult de koppeling uit de basis aan met zijn eigen credentials`() {
        schrijf("base.yaml", basis + "koppelingen:\n  zaken:\n    url: https://zaken.example.nl\n    auth:\n      type: zgw-jwt\n")
        schrijf("$bosrand.yaml", tenant(extra = "koppelingen:\n  zaken:\n    auth:\n      clientId: bosrand\n      secret: $bosrand.zaken-secret"))
        schrijf("$wijdeVeld.yaml", tenant(oin = wijdeVeld, domein = "wijdeveld.example", extra = "koppelingen:\n  zaken:\n    url: https://zaken.wijdeveld.example\n    auth:\n      clientId: wijdeveld"))

        val result = laad(meerdere = true)

        // Het Wijde Veld geeft geen secret op en erft er geen: onvolledig, met zijn OIN in de melding.
        assertThat(codes(result)).containsExactly(Code.KOPPELING_ONVOLLEDIG)
        assertThat(result.violations.single().bericht).contains(wijdeVeld).doesNotContain("$bosrand.zaken-secret")
        assertThat(result.violations.single().pad).isEqualTo("koppelingen.zaken.auth.secret")

        Files.delete(dir.resolve("$wijdeVeld.yaml"))
        val bosrandAlleen =
            laad()
                .tenants
                .single()
                .koppelingen
                .getValue("zaken")
        assertThat(bosrandAlleen.url).isEqualTo("https://zaken.example.nl")
        assertThat(bosrandAlleen.auth.clientId).isEqualTo("bosrand")
        assertThat(bosrandAlleen.auth.secret).isEqualTo(GeheimVerwijzing("$bosrand.zaken-secret"))
    }

    // --- de SPI zelf ---

    @Test
    fun `een modulenaam moet een geldige sleutel zijn`() {
        listOf("Proef", "1proef", "pro ef", "", "pro_ef").forEach { kandidaat ->
            val module =
                object : TenantModuleConfiguratie<ProefConfiguratie> {
                    override val naam = kandidaat
                    override val klasse = ProefConfiguratie::class
                }
            assertThrows<IllegalStateException> { TenantModules.van(listOf(module)) }
        }
    }

    @Test
    fun `twee modules met dezelfde naam weigeren het opstarten`() {
        val fout = assertThrows<IllegalStateException> { TenantModules.van(listOf(ProefModule(), ProefModule())) }

        assertThat(fout.message).contains("proef")
    }

    @Test
    fun `een configuratieklasse zonder primaire constructor kan niet in het schema`() {
        val module =
            object : TenantModuleConfiguratie<Interface> {
                override val naam = "kaal"
                override val klasse = Interface::class
            }

        val fout = assertThrows<IllegalStateException> { TenantModules.van(listOf(module)) }

        assertThat(fout.message).contains("geen primaire constructor")
    }

    @Test
    fun `een niet ondersteund veldtype weigert bij het bouwen van het schema, met het pad`() {
        val module =
            object : TenantModuleConfiguratie<ProefKommagetal> {
                override val naam = "kommagetal"
                override val klasse = ProefKommagetal::class
            }

        // Double is een klasse zonder primaire constructor-parameters die het schema kent: alleen geheel getal, tekst, boolean, duur.
        assertThrows<IllegalStateException> { TenantSchemaGenerator.generate(TenantModules.van(listOf(module))) }
    }

    @Test
    fun `een map met niet-tekst sleutels kan niet in het schema`() {
        val module =
            object : TenantModuleConfiguratie<ProefNummerMap> {
                override val naam = "nummers"
                override val klasse = ProefNummerMap::class
            }

        assertThrows<IllegalStateException> { TenantSchemaGenerator.generate(TenantModules.van(listOf(module))) }
    }

    @Test
    fun `de bijdragen worden gevonden via ServiceLoader, ook zonder Spring`() {
        val services = Files.createDirectories(dir.resolve("klassenpad/META-INF/services"))
        Files.writeString(services.resolve(TenantModuleConfiguratie::class.java.name), ProefModule::class.java.name + "\n")
        val loader = java.net.URLClassLoader(arrayOf(dir.resolve("klassenpad").toUri().toURL()), javaClass.classLoader)

        val gevonden = TenantModules.ontdek(loader)

        assertThat(gevonden.configuraties.map { it.naam }).containsExactly("proef")
        assertThat(gevonden.configuratie("proef")).isNotNull
        assertThat(gevonden.configuratie("onbekend")).isNull()
        assertThat(TenantModules.ontdek().configuraties).isEmpty()
    }

    @Test
    fun `het schema bevat de bijdragen van de modules onder modules en sluit de rest`() {
        val schema =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .readTree(TenantSchemaGenerator.generate(modules))

        val proef = schema["properties"]["modules"]["properties"]["proef"]
        assertThat(schema["properties"]["modules"]["additionalProperties"].asBoolean(true)).isFalse
        assertThat(proef["additionalProperties"].asBoolean(true)).isFalse
        assertThat(proef["required"].teksten()).containsExactly("brpVelden")
        assertThat(proef["properties"]["brpVelden"]["type"].asString()).isEqualTo("array")
        assertThat(proef["properties"]["steden"]["type"].asString()).isEqualTo("array")
        assertThat(proef["properties"]["actief"]["type"].asString()).isEqualTo("boolean")
        assertThat(proef["properties"]["maximum"]["type"].asString()).isEqualTo("integer")
        assertThat(proef["properties"]["grens"]["type"].asString()).isEqualTo("integer")
        assertThat(proef["properties"]["niveau"]["enum"].teksten()).containsExactly("LAAG", "HOOG")
        assertThat(proef["properties"]["timeout"]["type"].asString()).isEqualTo("string")
        assertThat(proef["properties"]["profielen"]["additionalProperties"]["required"].teksten()).containsExactly("apiKey")
        assertThat(proef["properties"]["geheim"]["pattern"].asString()).isEqualTo(GeheimVerwijzing.PATROON)
        assertThat(schema["properties"]["koppelingen"]["additionalProperties"]["required"].teksten()).containsExactly("url", "auth")
        assertThat(schema["properties"]["koppelingen"]["additionalProperties"]["properties"]["auth"]["properties"]["secret"]["pattern"].asString())
            .isEqualTo(GeheimVerwijzing.PATROON)
        assertThat(schema["properties"]["koppelingen"]["additionalProperties"]["properties"]["auth"]["properties"]["type"]["enum"].teksten())
            .containsExactly("api-key", "bearer", "zgw-jwt", "oauth2-client-credentials", "oauth2-private-key-jwt")
    }

    @Test
    fun `een geheimverwijzing is een naam en geen waarde`() {
        listOf("zaken-sleutel", "a", "zaken.sleutel_2").forEach { assertThat(GeheimVerwijzing(it).naam).isEqualTo(it) }
        listOf("", "Hoofdletter", "met spatie", "-begint", "eindigt-", "dubbel--streep", "Wachtwoord1!").forEach {
            assertThrows<IllegalArgumentException> { GeheimVerwijzing(it) }
        }
        assertThat(GeheimVerwijzing("zaken-sleutel").toString()).isEqualTo("geheim:zaken-sleutel")
    }

    interface Interface
}

private fun tools.jackson.databind.JsonNode.teksten(): List<String> = (0 until size()).map { get(it).asString() }