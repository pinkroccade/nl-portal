/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.commonground.authentication

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.time.Instant

/**
 * Stelt vast dat Spring Boot met `jwk-set-uri` en `issuer-uri` samen de issuer valideert, zonder
 * discovery bij het opstarten (#480). Dezelfde vorm als `backend/app/.../application.yml`.
 */
internal class IssuerValidationTest {
    private lateinit var keycloak: MockWebServer
    private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()

    @BeforeEach
    fun setUp() {
        keycloak = MockWebServer()
        keycloak.start()
    }

    @AfterEach
    fun tearDown() {
        keycloak.shutdown()
    }

    private fun contextRunner() =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReactiveOAuth2ResourceServerAutoConfiguration::class.java))
            .withPropertyValues(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${keycloak.url("/certs")}",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=$ISSUER",
            )

    @Test
    fun `the decoder starts without contacting keycloak`() {
        contextRunner().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(ReactiveJwtDecoder::class.java)
            assertThat(keycloak.requestCount).isZero()
        }
    }

    @Test
    fun `a token from the expected issuer is accepted`() {
        keycloak.enqueue(jwks())
        contextRunner().run { context ->
            val jwt = context.getBean(ReactiveJwtDecoder::class.java).decode(signedToken(ISSUER)).block()

            assertThat(jwt!!.issuer.toString()).isEqualTo(ISSUER)
        }
    }

    @Test
    fun `a validly signed token from another issuer is rejected`() {
        keycloak.enqueue(jwks())
        contextRunner().run { context ->
            val decoder = context.getBean(ReactiveJwtDecoder::class.java)

            assertThatThrownBy { decoder.decode(signedToken("http://attacker.invalid/auth/realms/de-bosrand")).block() }
                .isInstanceOf(JwtValidationException::class.java)
                .hasMessageContaining("iss")
        }
    }

    private fun jwks(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(JWKSet(signingKey.toPublicJWK()).toString())

    private fun signedToken(issuer: String): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .subject("burger")
                .issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
        jwt.sign(RSASSASigner(signingKey))
        return jwt.serialize()
    }

    companion object {
        private const val ISSUER = "http://localhost:8082/auth/realms/de-bosrand"
    }
}