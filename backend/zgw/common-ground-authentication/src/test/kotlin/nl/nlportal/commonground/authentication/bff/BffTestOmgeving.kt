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
package nl.nlportal.commonground.authentication.bff

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.oauth2.sdk.TokenRequest
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.OAuth2Config
import no.nav.security.mock.oauth2.token.OAuth2TokenCallback
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import nl.nlportal.core.tenancy.Authenticatie
import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.Omgevingstype
import nl.nlportal.core.tenancy.Sessie
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantWebFilter
import nl.nlportal.core.tenancy.Thema
import nl.nlportal.core.tenancy.TrustedProxies
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import kotlinx.coroutines.reactor.awaitSingle
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.session.ReactiveMapSessionRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Een bestuurbare klok voor de timeouts. */
class MutableClock : Clock() {
    private val nu = AtomicReference(Instant.now())

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = nu.get()

    fun advance(duur: Duration) {
        nu.updateAndGet { it.plus(duur) }
    }

    fun reset() {
        nu.set(Instant.now())
    }
}

/** Wat de testprovider bij de volgende code-uitwisseling in het ID-token zet (per issuer, geen gedeelde wachtrij). */
data class TokenSpec(
    val subject: String = "gebruiker-1",
    val sid: String = "sid-1",
    val inlogmiddel: String? = "digid",
    val acr: Any? = DIGID_SUBSTANTIEEL,
    val actingSubjectId: Any? = "s00000000:999993653",
    val extra: Map<String, Any> = emptyMap(),
    val metAuthTime: Boolean = true,
    /** Verschuiving van `auth_time` ten opzichte van de klok van de test. */
    val authTimeOffset: Duration = Duration.ZERO,
    /** Levensduur van het ID-token; negatief geeft een token dat al verlopen is. */
    val geldigheid: Duration = Duration.ofHours(1),
) {
    companion object {
        const val DIGID_BASIS = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport"
        const val DIGID_MIDDEN = "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract"
        const val DIGID_SUBSTANTIEEL = "urn:oasis:names:tc:SAML:2.0:ac:classes:Smartcard"
        const val DIGID_HOOG = "urn:oasis:names:tc:SAML:2.0:ac:classes:SmartcardPKI"
    }
}

class GestuurdeTokens(
    private val issuer: String,
    private val klok: Clock,
) : OAuth2TokenCallback {
    @Volatile
    var clientId = ""

    @Volatile
    var spec = TokenSpec()

    override fun issuerId() = issuer

    override fun subject(tokenRequest: TokenRequest) = spec.subject

    override fun typeHeader(tokenRequest: TokenRequest) = "JWT"

    override fun audience(tokenRequest: TokenRequest) = listOf(clientId)

    override fun addClaims(tokenRequest: TokenRequest): Map<String, Any> =
        buildMap {
            put("sid", spec.sid)
            spec.inlogmiddel?.let { put("inlogmiddel", it) }
            spec.acr?.let { put("acr", it) }
            if (spec.metAuthTime) put("auth_time", klok.instant().plus(spec.authTimeOffset).epochSecond)
            putAll(spec.extra)
        }

    override fun tokenExpiry() = spec.geldigheid.seconds
}

/** Een tenant van de testomgeving: de definitie en de bijbehorende sleutel en testprovider. */
class TestTenant(
    val naam: String,
    val oin: Oin,
    val host: String,
    val sleutel: RSAKey,
    val tokens: GestuurdeTokens,
    val definitie: TenantDefinition,
) {
    val clientId get() = definitie.authenticatie.clientId
    val issuer get() = definitie.authenticatie.issuer
}

/** De hele testomgeving: een klok, een provider met een issuer per tenant, een gevangen eventlogboek en twee tenants. */
object BffTestOmgeving {
    val klok = MutableClock()
    val gebeurtenissen = CopyOnWriteArrayList<Beveiligingsgebeurtenis>()

    private val tokenCallbacks = listOf("bosrand", "wijdeveld").associateWith { GestuurdeTokens(it, klok) }

    val provider: MockOAuth2Server = MockOAuth2Server(OAuth2Config(tokenCallbacks = tokenCallbacks.values.toSet())).also { it.start() }

    /**
     * De userinfo van de testprovider. De mock zet alle claims van `addClaims` zowel in het ID-token als in het access
     * token (en dus in de userinfo); de echte realm zet het BSN alleen in de userinfo. Daarom een eigen eindpunt per tenant.
     */
    private val userinfoServer: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { server ->
            tokenCallbacks.forEach { (naam, tokens) ->
                server.createContext("/userinfo/$naam") { uitwisseling ->
                    val bsn =
                        tokens.spec.actingSubjectId
                            ?.let { ",\"acting_subject_id\":\"$it\"" }
                            .orEmpty()
                    val antwoord = "{\"sub\":\"${tokens.spec.subject}\"$bsn}".toByteArray()
                    uitwisseling.responseHeaders.add("Content-Type", "application/json")
                    uitwisseling.sendResponseHeaders(200, antwoord.size.toLong())
                    uitwisseling.responseBody.use { it.write(antwoord) }
                }
            }
            server.start()
        }

    val userinfoPoort: Int get() = userinfoServer.address.port

    /** De sleutelmap (`nl-portal.sleutelmap`): per tenant een map met de OIN als naam. */
    val sleutelMap: java.nio.file.Path = Files.createTempDirectory("bff-test-sleutels")

    private fun tenant(
        naam: String,
        oin: String,
        host: String,
        inlogmiddelen: Set<Inlogmiddel>,
        niveau: Betrouwbaarheidsniveau,
        sessie: Sessie = Sessie(),
    ): TestTenant {
        val sleutel = RSAKeyGenerator(3072).keyID("$naam-sleutel-1").algorithm(JWSAlgorithm.PS256).generate()
        val bestand = sleutelMap.resolve(oin).resolve("$naam.jwk")
        Files.createDirectories(bestand.parent)
        Files.writeString(bestand, sleutel.toJSONString())
        val tokens = tokenCallbacks.getValue(naam)
        val definitie =
            TenantDefinition(
                oin = Oin(oin),
                naam = "Gemeente $naam",
                omgevingstype = Omgevingstype.ONTWIKKELING,
                domeinen = listOf(host),
                thema = Thema(titel = naam),
                inlogmiddelen = inlogmiddelen,
                authenticatie = Authenticatie(issuer = provider.issuerUrl(naam).toString(), sleutelBestand = bestand.toString()),
                minimumNiveau = niveau,
                sessie = sessie,
            )
        tokens.clientId = definitie.authenticatie.clientId
        return TestTenant(naam, Oin(oin), host, sleutel, tokens, definitie)
    }

    /** De Bosrand: DigiD en het testinlogmiddel, minimaal Basis, standaard sessietijden. */
    val bosrand = tenant("bosrand", "00000099000000000001", "bosrand.test", setOf(Inlogmiddel.DIGID, Inlogmiddel.TEST), Betrouwbaarheidsniveau.BASIS)

    /** Het Wijde Veld: alleen DigiD, minimaal Substantieel, strengere sessietijden. */
    val wijdeveld =
        tenant("wijdeveld", "00000099000000000002", "wijdeveld.test", setOf(Inlogmiddel.DIGID), Betrouwbaarheidsniveau.SUBSTANTIEEL, Sessie(Duration.ofMinutes(10), Duration.ofHours(2)))

    val tenants = listOf(bosrand, wijdeveld)

    /** Een bron achter een uitgaande koppeling: elk verzoek dat hier aankomt is een uitgaande aanroep van de app. */
    val bron: MockWebServer =
        MockWebServer().also { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setBody("bron")
                }
            server.start()
        }
}

@RestController
class TestEindpunten {
    @GetMapping("/api/test/wie")
    fun wie(): Mono<Map<String, String>> =
        ReactiveSecurityContextHolder.getContext().map {
            val authentication = it.authentication as nl.nlportal.portal.authentication.domain.PortalAuthentication
            mapOf(
                "soort" to authentication.identiteit.vertegenwoordigde.soort.code,
                "bsn" to authentication.identiteit.vertegenwoordigde.identificatie,
                "sub" to authentication.identiteit.gebruiker.subject,
                "niveau" to authentication.identiteit.gebruiker.niveau.waarde,
            )
        }

    /** Een beveiligd eindpunt dat uitgaand verkeer veroorzaakt, zoals een module die via een koppeling de bron raadpleegt. */
    @GetMapping("/api/test/koppeling")
    fun koppeling(): Mono<String> =
        WebClient
            .create("http://localhost:${BffTestOmgeving.bron.port}")
            .get()
            .uri("/zaken")
            .retrieve()
            .bodyToMono(String::class.java)

    @PostMapping("/api/test/mutatie")
    fun mutatie() = "ok"

    @PostMapping("/api/public/payment/direct/postsale")
    fun webhook() = "ok"
}

/** Een GraphQL-resolver zoals de modules die hebben: de identiteit komt als `PortalAuthentication`, en de bron wordt aangeroepen. */
@Controller
class TestGraphQl {
    @QueryMapping
    suspend fun bffTestWie(authentication: PortalAuthentication): String {
        WebClient
            .create("http://localhost:${BffTestOmgeving.bron.port}")
            .get()
            .uri("/zaken")
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingle()
        return authentication.identiteit.gebruiker.subject
    }

    @MutationMapping
    suspend fun bffTestMutatie(authentication: PortalAuthentication): String {
        WebClient
            .create("http://localhost:${BffTestOmgeving.bron.port}")
            .post()
            .uri("/zaken")
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingle()
        return authentication.identiteit.gebruiker.subject
    }
}

@SpringBootApplication
class BffTestApplication

@Configuration
class BffTestConfiguratie {
    @Bean
    fun testKlok(): Clock = BffTestOmgeving.klok

    @Bean
    fun tenantDirectory() = TenantDirectory(BffTestOmgeving.tenants.map { it.definitie })

    @Bean
    fun tenantWebFilter(directory: TenantDirectory) = TenantWebFilter(directory, TrustedProxies(emptyList()))

    @Bean
    fun eenmaligeOpslag(clock: Clock): EenmaligeOpslag = GeheugenEenmaligeOpslag(clock)

    @Bean
    fun gevangenGebeurtenissen(): Beveiligingsgebeurtenissen =
        Beveiligingsgebeurtenissen {
            BffTestOmgeving.gebeurtenissen += it
            LogBeveiligingsgebeurtenissen().registreer(it)
        }

    /** De testprovider heeft geen Keycloak-paden maar `/{issuer}/authorize`, `/token`, `/jwks` en `/endsession`. */
    @Bean
    fun testprovider(): IdpEindpuntenIndeling =
        IdpEindpuntenIndeling { issuer, _ ->
            val id = issuer.substringAfterLast('/')
            val server = BffTestOmgeving.provider
            IdpEindpunten(
                server.authorizationEndpointUrl(id).toString(),
                server.tokenEndpointUrl(id).toString(),
                server.jwksUrl(id).toString(),
                server.endSessionEndpointUrl(id).toString(),
                "http://127.0.0.1:${BffTestOmgeving.userinfoPoort}/userinfo/$id",
            )
        }

    @Bean
    fun sessieRepository(): ReactiveMapSessionRepository = ReactiveMapSessionRepository(ConcurrentHashMap()).also { it.setSessionIdGenerator(SecureSessionIdGenerator()) }
}