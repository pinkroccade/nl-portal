package nl.nlportal.commonground.authentication.bff

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import nl.nlportal.core.tenancy.Authenticatie
import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.Omgevingstype
import nl.nlportal.core.tenancy.Sessie
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.Thema
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class InlogbeslisserTest {
    private val nu = Instant.parse("2026-11-21T12:00:00Z")
    private val beslisser = Inlogbeslisser()

    private fun tenant(
        middelen: Set<Inlogmiddel> = setOf(Inlogmiddel.DIGID, Inlogmiddel.WALLET, Inlogmiddel.EHERKENNING, Inlogmiddel.TEST),
        minimum: Betrouwbaarheidsniveau = Betrouwbaarheidsniveau.BASIS,
    ) = TenantDefinition(
        Oin("00000099000000000001"),
        "T",
        Omgevingstype.ONTWIKKELING,
        listOf("t.test"),
        Thema(titel = "t"),
        middelen,
        Authenticatie("https://login.example/realms/t", "/k.jwk"),
        minimum,
        Sessie(),
    )

    private fun token(
        wijzig: MutableMap<String, Any>.() -> Unit = {},
    ): OidcIdToken {
        val claims =
            mutableMapOf<String, Any>(
                "iss" to "https://login.example/realms/t",
                "sub" to "s",
                "aud" to listOf("nl-portal-backend"),
                "iat" to nu,
                "exp" to nu.plusSeconds(60),
                "auth_time" to nu,
                "inlogmiddel" to "digid",
                "acr" to TokenSpec.DIGID_SUBSTANTIEEL,
                "acting_subject_id" to "s00000000:999993653",
            )
        claims.wijzig()
        return OidcIdToken("t", nu, nu.plusSeconds(60), claims)
    }

    private fun reden(
        t: OidcIdToken,
        tenant: TenantDefinition = tenant(),
        start: Instant? = nu,
    ) = (beslisser.beslis(tenant, t, start) as? Inlogbeslissing.Geweigerd)?.reden

    @Test
    fun `een geldig DigiD-token geeft een burger met inlogmiddel en niveau`() {
        val beslissing = beslisser.beslis(tenant(), token(), nu) as Inlogbeslissing.Toegestaan

        assertThat(beslissing.identiteit.vertegenwoordigde.identificatie).isEqualTo("999993653")
        assertThat(beslissing.identiteit.gebruiker.inlogmiddel).isEqualTo(Inlogmiddel.DIGID)
        assertThat(beslissing.identiteit.gebruiker.niveau).isEqualTo(Betrouwbaarheidsniveau.SUBSTANTIEEL)
    }

    @Test
    fun `wallet en eHerkenning worden geweigerd ook als de tenant ze aan heeft staan`() {
        assertThat(reden(token { put("inlogmiddel", "wallet"); put("acr", "http://eidas.europa.eu/LoA/substantial") })).isEqualTo(Reden.INLOGMIDDEL_NIET_ONDERSTEUND)
        assertThat(reden(token { put("inlogmiddel", "eherkenning") })).isEqualTo(Reden.INLOGMIDDEL_NIET_ONDERSTEUND)
    }

    @Test
    fun `de volgordetabel van DigiD en de eIDAS-URI's`() {
        val digid = Inlogbeslisser.NIVEAUS.getValue(Inlogmiddel.DIGID)
        assertThat(digid.values).containsExactly(Betrouwbaarheidsniveau.BASIS, Betrouwbaarheidsniveau.MIDDEN, Betrouwbaarheidsniveau.SUBSTANTIEEL, Betrouwbaarheidsniveau.HOOG)
        assertThat(Inlogbeslisser.NIVEAUS.getValue(Inlogmiddel.WALLET).keys).containsExactly("http://eidas.europa.eu/LoA/low", "http://eidas.europa.eu/LoA/substantial", "http://eidas.europa.eu/LoA/high")
        assertThat(Inlogbeslisser.NIVEAUS.getValue(Inlogmiddel.TEST)).containsExactly(org.assertj.core.api.Assertions.entry("Basis", Betrouwbaarheidsniveau.BASIS))
        assertThat(Inlogbeslisser.NIVEAUS.getValue(Inlogmiddel.EHERKENNING)).isEmpty()
    }

    @Test
    fun `auth_time mag hooguit de marge voor het begin van de login liggen`() {
        assertThat(reden(token { put("auth_time", nu.minus(Inlogbeslisser.AUTH_TIME_MARGE)) })).isNull()
        assertThat(reden(token { put("auth_time", nu.minus(Inlogbeslisser.AUTH_TIME_MARGE).minusSeconds(1)) })).isEqualTo(Reden.AUTH_TIME)
        assertThat(reden(token(), start = null)).isEqualTo(Reden.AUTH_TIME)
    }

    @Test
    fun `een andere issuer of client wordt geweigerd`() {
        assertThat(reden(token { put("iss", "https://login.example/realms/andere") })).isEqualTo(Reden.ISSUER)
        assertThat(reden(token { put("aud", listOf("andere-client")) })).isEqualTo(Reden.CLIENT)
        assertThat(reden(token { put("azp", "andere-client") })).isEqualTo(Reden.CLIENT)
        assertThat(reden(token { put("sub", " ") })).isEqualTo(Reden.SUBJECT)
    }

    @ParameterizedTest
    @CsvSource("999993653,true", "999993847,true", "123456782,true", "123456789,false", "000000000,false", "99999365,false", "9999936534,false", "99999365a,false", "'',false")
    fun `een BSN heeft negen cijfers met een geldige elfproef`(
        nummer: String,
        geldig: Boolean,
    ) {
        assertThat(Inlogbeslisser.geldigBsn(nummer)).isEqualTo(geldig)
    }

    @Test
    fun `de Betrouwbaarheidsniveaus zijn geordend zoals de tabel`() {
        assertThat(Betrouwbaarheidsniveau.BASIS < Betrouwbaarheidsniveau.MIDDEN).isTrue()
        assertThat(Betrouwbaarheidsniveau.SUBSTANTIEEL < Betrouwbaarheidsniveau.HOOG).isTrue()
    }
}

class ClientSleutelsTest {
    @TempDir
    lateinit var dir: Path

    private fun sleutel(
        kid: String? = "k1",
        alg: JWSAlgorithm? = JWSAlgorithm.PS256,
        bits: Int = 3072,
    ): RSAKey = RSAKeyGenerator(bits).also { g -> kid?.let { g.keyID(it) }; alg?.let { g.algorithm(it) } }.generate()

    private fun tenant(
        oin: String,
        bestand: String,
    ) = TenantDefinition(
        Oin(oin),
        "T",
        Omgevingstype.ONTWIKKELING,
        listOf("$oin.test"),
        Thema(titel = "t"),
        setOf(Inlogmiddel.TEST),
        Authenticatie("https://login.example/realms/$oin", bestand),
        Betrouwbaarheidsniveau.BASIS,
        Sessie(),
    )

    private fun schrijf(
        naam: String,
        inhoud: String,
    ): String = dir.resolve(naam).also { Files.writeString(it, inhoud) }.toString()

    private val a = "00000099000000000001"
    private val b = "00000099000000000002"

    @Test
    fun `een geldige sleutel per tenant laadt, een relatief pad is relatief aan de tenantmap`() {
        schrijf("a.jwk", sleutel("ka").toJSONString())
        schrijf("b.jwk", sleutel("kb").toJSONString())

        val sleutels = TenantClientSleutels.laad(listOf(tenant(a, "a.jwk"), tenant(b, dir.resolve("b.jwk").toString())), dir)

        assertThat(sleutels.voor(Oin(a)).keyID).isEqualTo("ka")
        assertThat(sleutels.voor(Oin(b)).keyID).isEqualTo("kb")
        assertThatThrownBy { sleutels.voor(Oin("00000099000000000009")) }.hasMessageContaining("Geen clientsleutel")
    }

    private fun weigert(
        inhoud: String?,
        bevat: String,
    ) {
        val bestand = if (inhoud == null) dir.resolve("bestaat-niet.jwk").toString() else schrijf("x.jwk", inhoud)
        assertThatThrownBy { TenantClientSleutels.laad(listOf(tenant(a, bestand)), null) }.hasMessageContaining(bevat).hasMessageContaining("Tenant $a")
    }

    @Test
    fun `een onbruikbare sleutel laat de runtime niet starten en toont geen sleuteldelen`() {
        weigert(null, "ontbreekt")
        weigert("geen json", "geen geldige JWK")
        weigert(sleutel().toPublicJWK().toJSONString(), "niet privé")
        weigert(sleutel(kid = null).toJSONString(), "kid")
        weigert(sleutel(alg = null).toJSONString(), "PS256")
        weigert(sleutel(alg = JWSAlgorithm.RS256).toJSONString(), "PS256")
        weigert(sleutel(bits = 2048).toJSONString(), "3072")
        weigert(com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).keyID("e").generate().toJSONString(), "geen RSA")
    }

    @Test
    fun `twee tenants met dezelfde keyId of dezelfde sleutel laten de runtime niet starten`() {
        val gedeeld = sleutel("zelfde")
        schrijf("a.jwk", sleutel("zelfde").toJSONString())
        schrijf("b.jwk", sleutel("zelfde").toJSONString())
        schrijf("c.jwk", gedeeld.toJSONString())
        schrijf("d.jwk", RSAKey.Builder(gedeeld.toRSAPublicKey()).privateKey(gedeeld.toRSAPrivateKey()).keyID("andere-kid").algorithm(JWSAlgorithm.PS256).build().toJSONString())

        assertThatThrownBy { TenantClientSleutels.laad(listOf(tenant(a, "a.jwk"), tenant(b, "b.jwk")), dir) }.hasMessageContaining("keyId")
        assertThatThrownBy { TenantClientSleutels.laad(listOf(tenant(a, "c.jwk"), tenant(b, "d.jwk")), dir) }.hasMessageContaining("dezelfde clientsleutel")
    }
}

class BrokerBasisTest {
    private fun valtOnder(
        issuer: String,
        broker: String,
    ) = BrokerBasis.valtOnder(URI.create(issuer), URI.create(broker))

    @Test
    fun `de issuer valt onder de basis-URL op schema, host, poort en padsegmenten`() {
        assertThat(valtOnder("https://login.example/realms/x", "https://login.example")).isTrue()
        assertThat(valtOnder("https://login.example/auth/realms/x", "https://login.example/auth/")).isTrue()
        assertThat(valtOnder("https://LOGIN.example/realms/x", "https://login.example")).isTrue()
        assertThat(valtOnder("https://login.example:443/realms/x", "https://login.example")).isTrue()
        assertThat(valtOnder("http://login.example/realms/x", "https://login.example")).isFalse()
        assertThat(valtOnder("https://login.example.evil.nl/realms/x", "https://login.example")).isFalse()
        assertThat(valtOnder("https://login.example:8443/realms/x", "https://login.example")).isFalse()
        assertThat(valtOnder("https://login.example/authx/realms/x", "https://login.example/auth")).isFalse()
        assertThat(valtOnder("https://login.example/realms/x", "https://login.example/auth")).isFalse()
    }
}

class GeheugenEenmaligeOpslagTest {
    private val klok = MutableClock()
    private val opslag = GeheugenEenmaligeOpslag(klok)

    @Test
    fun `een waarde is hooguit een keer te nemen en verloopt na de ttl`() {
        opslag.zet("k", "v", Duration.ofSeconds(10)).block()

        assertThat(opslag.lees("k").block()).isEqualTo("v")
        assertThat(opslag.neem("k").block()).isEqualTo("v")
        assertThat(opslag.neem("k").block()).isNull()

        opslag.zet("k", "v", Duration.ofSeconds(10)).block()
        klok.advance(Duration.ofSeconds(10))
        assertThat(opslag.lees("k").block()).isNull()
        opslag.zet("k2", "v", Duration.ofSeconds(10)).block()
        opslag.verwijder("k2").block()
        assertThat(opslag.lees("k2").block()).isNull()
    }
}

class LogBeveiligingsgebeurtenissenTest {
    @Test
    fun `een event is een logregel met vaste velden en zonder stuurtekens uit een iss`() {
        val logger = LoggerFactory.getLogger("nl.nlportal.beveiliging") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            LogBeveiligingsgebeurtenissen().registreer(
                Beveiligingsgebeurtenis(GebeurtenisType.LOGIN_GEWEIGERD, "00000099000000000001", Actor("https://x.example\nvals=1", "sub 1"), "geweigerd", Reden.NIVEAU_TE_LAAG, "c-1"),
            )
        } finally {
            logger.detachAppender(appender)
        }

        val regel = appender.list.single().formattedMessage
        assertThat(regel).isEqualTo("beveiligingsevent type=login_geweigerd oin=00000099000000000001 actor=https://x.example_vals_1|sub_1 uitkomst=geweigerd reden=niveau_te_laag correlatie=c-1")
    }
}
