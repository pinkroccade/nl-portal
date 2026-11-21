package nl.nlportal.proto

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.SignedJWT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.OAuth2Config
import no.nav.security.mock.oauth2.token.OAuth2TokenCallback
import com.nimbusds.oauth2.sdk.TokenRequest
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Een bestuurbare klok voor de absolute sessietimeout. */
class MutableClock : Clock() {
    private val now = AtomicReference(Instant.now())

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now.get()

    fun advance(d: Duration) {
        now.updateAndGet { it.plus(d) }
    }

    fun reset() {
        now.set(Instant.now())
    }
}

@TestConfiguration
class TestClockConfig {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock()
}

class TenantFixture(val id: String, val host: String, val clientId: String, val key: RSAKey)

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig::class)
abstract class BffTestBase {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var redis: ReactiveStringRedisTemplate

    @Autowired
    lateinit var clock: MutableClock

    lateinit var client: WebTestClient

    @BeforeEach
    fun setUpClient() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(20)).build()
        clock.reset()
        // verouderde verzoeken van een vorige test weghalen
        drainMockRequests()
    }

    private fun drainMockRequests() {
        try {
            while (true) server.takeRequest(10, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            // geen verzoeken meer
        }
    }

    @AfterEach
    fun flush() {
        redis.execute { it.serverCommands().flushAll() }.blockLast()
    }

    // ---- Hulpfuncties: een browser nabootsen (cookies zelf bijhouden, geen redirects volgen) ----

    class Browser(val host: String) {
        val jar = linkedMapOf<String, String>()

        fun cookieHeader(): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

        fun absorb(headers: HttpHeaders) {
            headers[HttpHeaders.SET_COOKIE]?.forEach { raw ->
                val c = java.net.HttpCookie.parse(raw).first()
                if (c.maxAge == 0L) jar.remove(c.name) else jar[c.name] = c.value
            }
        }
    }

    fun get(b: Browser, path: String): EntityExchangeResult<ByteArray> {
        val r = client.get().uri(URI.create("http://localhost:$port$path")).header("Host", "${b.host}:$port")
            .header("Cookie", b.cookieHeader()).exchange().expectBody().returnResult()
        b.absorb(r.responseHeaders)
        return r
    }

    fun post(b: Browser, path: String): EntityExchangeResult<ByteArray> {
        val r = client.post().uri(URI.create("http://localhost:$port$path")).header("Host", "${b.host}:$port")
            .header("Cookie", b.cookieHeader()).exchange().expectBody().returnResult()
        b.absorb(r.responseHeaders)
        return r
    }

    /** De IdP doet (non-interactief) een redirect terug naar de redirect_uri met code en state. */
    fun idpAuthorize(authorizeUri: URI): URI {
        val r = WebTestClient.bindToServer().baseUrl("${authorizeUri.scheme}://${authorizeUri.authority}").build()
            .get().uri(authorizeUri).exchange().expectBody().returnResult()
        check(r.status.value() == 302) { "authorize gaf ${r.status}" }
        return r.responseHeaders.location!!
    }

    fun callback(b: Browser, callbackUri: URI, iss: String?): EntityExchangeResult<ByteArray> {
        val q = callbackUri.rawQuery + (iss?.let { "&iss=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
        return get(b, callbackUri.rawPath + "?" + q)
    }

    fun enqueueTokens(t: TenantFixture, subject: String = "999993653", sid: String = "sid-" + java.util.UUID.randomUUID()) {
        loginCallbacks.getValue(t.id).next(t.clientId, subject, sid)
    }

    class LoginResult(val browser: Browser, val callback: EntityExchangeResult<ByteArray>, val sid: String)

    fun login(
        t: TenantFixture,
        b: Browser = Browser(t.host),
        subject: String = "999993653",
        sid: String = "sid-" + java.util.UUID.randomUUID(),
    ): LoginResult {
        enqueueTokens(t, subject, sid)
        val start = get(b, "/oauth2/authorization/${t.id}")
        check(start.status.value() == 302) { "login-start gaf ${start.status}" }
        val back = idpAuthorize(start.responseHeaders.location!!)
        return LoginResult(b, callback(b, back, t.issuer), sid)
    }

    val TenantFixture.issuer: String get() = server.issuerUrl(id).toString()

    /** Het verzoek dat het token-endpoint van deze tenant ontving. */
    fun tokenRequest(t: TenantFixture): RecordedRequest {
        repeat(50) {
            val r = server.takeRequest(5, TimeUnit.SECONDS) ?: error("geen tokenverzoek voor ${t.id}")
            if (r.requestUrl?.encodedPath == "/${t.id}/token") return r
        }
        error("geen tokenverzoek voor ${t.id}")
    }

    /** De client-assertion: handtekening, kid, iss, sub, aud en jti; en geen client_secret. */
    fun verifyAssertion(req: RecordedRequest, t: TenantFixture): SignedJWT {
        val form = req.body.readUtf8().split("&").associate {
            it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("=", ""), Charsets.UTF_8)
        }
        check("client_secret" !in form) { "client_secret meegestuurd" }
        check(req.getHeader("Authorization") == null) { "Authorization-header meegestuurd" }
        check(form["client_assertion_type"] == "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
        val jwt = SignedJWT.parse(form.getValue("client_assertion"))
        check(jwt.verify(RSASSAVerifier(t.key.toPublicJWK()))) { "handtekening klopt niet met de sleutel van ${t.id}" }
        check(jwt.header.algorithm == JWSAlgorithm.PS256)
        check(jwt.header.keyID == t.key.keyID)
        check(jwt.jwtClaimsSet.issuer == t.clientId && jwt.jwtClaimsSet.subject == t.clientId)
        check(jwt.jwtClaimsSet.audience.single() == t.issuer) { "aud moet de issuer zijn" }
        check(jwt.header.type?.toString() == "client-authentication+jwt") { "typ: ${jwt.header.type}" }
        check(java.util.Base64.getUrlDecoder().decode(jwt.jwtClaimsSet.jwtid).size >= 32) { "jti korter dan 256 bit" }
        check(jwt.jwtClaimsSet.jwtid != null && jwt.jwtClaimsSet.expirationTime != null)
        return jwt
    }

    companion object {
        val redisContainer: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379).also { it.start() }

        /** Per issuer bepaalt de test wie er bij de volgende code-uitwisseling inlogt (geen gedeelde wachtrij). */
        val loginCallbacks = listOf("bosrand", "wijdeveld").associateWith { ControlledCallback(it) }

        val server = MockOAuth2Server(OAuth2Config(tokenCallbacks = loginCallbacks.values.toSet())).also { it.start() }

        private val tempDir = Files.createTempDirectory("bff-proto")

        val bosrand = fixture("bosrand", "bosrand.test")
        val wijdeveld = fixture("wijdeveld", "wijdeveld.test")

        private fun fixture(id: String, host: String): TenantFixture {
            val key = RSAKeyGenerator(3072).keyID("$id-key-1").algorithm(JWSAlgorithm.PS256).generate()
            Files.writeString(tempDir.resolve("$id.jwk"), key.toJSONString())
            return TenantFixture(id, host, "nl-portal-backend-$id", key)
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.data.redis.host") { redisContainer.host }
            r.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
            listOf(bosrand, wijdeveld).forEachIndexed { i, t ->
                val p = "proto.tenants[$i]"
                r.add("$p.id") { t.id }
                r.add("$p.hosts[0]") { t.host }
                r.add("$p.client-id") { t.clientId }
                r.add("$p.issuer") { server.issuerUrl(t.id).toString() }
                r.add("$p.authorization-uri") { server.authorizationEndpointUrl(t.id).toString() }
                r.add("$p.token-uri") { server.tokenEndpointUrl(t.id).toString() }
                r.add("$p.jwk-set-uri") { server.jwksUrl(t.id).toString() }
                r.add("$p.end-session-uri") { server.endSessionEndpointUrl(t.id).toString() }
                r.add("$p.key-file") { tempDir.resolve("${t.id}.jwk").toString() }
            }
        }
    }
}

class ControlledCallback(private val issuer: String) : OAuth2TokenCallback {
    @Volatile
    private var clientId = ""

    @Volatile
    private var subject = "999993653"

    @Volatile
    private var sid = "sid"

    fun next(clientId: String, subject: String, sid: String) {
        this.clientId = clientId
        this.subject = subject
        this.sid = sid
    }

    override fun issuerId() = issuer

    override fun subject(tokenRequest: TokenRequest) = subject

    override fun typeHeader(tokenRequest: TokenRequest) = "JWT"

    override fun audience(tokenRequest: TokenRequest) = listOf(clientId)

    override fun addClaims(tokenRequest: TokenRequest): Map<String, Any> = mapOf("sid" to sid)

    override fun tokenExpiry() = 3600L
}
