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

import nl.nlportal.portal.authentication.service.PortalAuthenticationConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.net.URI

internal class AuthenticationConfigurationTest {
    private val runnerWithoutIssuer =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthenticationConfiguration::class.java))
            .withUserConfiguration(JwtDecoderConfiguration::class.java)

    private val contextRunner =
        runnerWithoutIssuer.withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=$ISSUER")

    @Test
    fun `without a token-uri the token exchange goes to the token endpoint of the configured issuer`() {
        contextRunner.run { context ->
            assertThat(converter(context).tokenEndpoint)
                .isEqualTo(URI.create("$ISSUER/protocol/openid-connect/token"))
        }
    }

    @Test
    fun `a configured token-uri wins over the issuer`() {
        contextRunner
            .withPropertyValues("nl-portal.authentication.keycloak.token-uri=$INTERNAL_TOKEN_URI")
            .run { context ->
                assertThat(converter(context).tokenEndpoint).isEqualTo(URI.create(INTERNAL_TOKEN_URI))
            }
    }

    @Test
    fun `a blank token-uri falls back to the issuer`() {
        contextRunner
            .withPropertyValues("nl-portal.authentication.keycloak.token-uri=")
            .run { context ->
                assertThat(converter(context).tokenEndpoint)
                    .isEqualTo(URI.create("$ISSUER/protocol/openid-connect/token"))
            }
    }

    @Test
    fun `without an issuer the context does not start and names the missing variable`() {
        runnerWithoutIssuer
            .withPropertyValues("nl-portal.authentication.keycloak.token-uri=$INTERNAL_TOKEN_URI")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("KEYCLOAK_ISSUER_URI")
            }
    }

    @Test
    fun `a context without a configured keycloak client starts`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(PortalAuthenticationConverter::class.java)
        }
    }

    @Test
    fun `a configuration without an audience starts`() {
        contextRunner
            .withPropertyValues(
                "nl-portal.authentication.keycloak.resource=nl-portal-m2m",
                "nl-portal.authentication.keycloak.audience=",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PortalAuthenticationConverter::class.java)
            }
    }

    @Test
    fun `a configuration with an audience starts`() {
        contextRunner
            .withPropertyValues(
                "nl-portal.authentication.keycloak.resource=nl-portal-m2m",
                "nl-portal.authentication.keycloak.audience=nl-portal-m2m",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PortalAuthenticationConverter::class.java)
            }
    }

    private fun converter(context: AssertableApplicationContext): CommonGroundAuthenticationConverter = context.getBean(PortalAuthenticationConverter::class.java) as CommonGroundAuthenticationConverter

    companion object {
        private const val ISSUER = "http://localhost:8082/auth/realms/de-bosrand"
        private const val INTERNAL_TOKEN_URI =
            "http://nl-portal-keycloak:8082/auth/realms/de-bosrand/protocol/openid-connect/token"
    }

    @Configuration
    internal class JwtDecoderConfiguration {
        @Bean
        fun reactiveJwtDecoder(): ReactiveJwtDecoder = mock()
    }
}