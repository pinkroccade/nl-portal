/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.core.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono

internal class OauthSecurityAutoConfigurationTest {
    private val runner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OauthSecurityAutoConfiguration::class.java))
            .withUserConfiguration(DecoderConfiguration::class.java)

    @Test
    fun `without a jwt converter the context does not start, there is no second authentication path`() {
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseInstanceOf(NoSuchBeanDefinitionException::class.java)
        }
    }

    @Test
    fun `with a jwt converter the security filter chain starts`() {
        runner.withUserConfiguration(ConverterConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
        }
    }

    @Configuration
    class DecoderConfiguration {
        @Bean
        fun decoder(): ReactiveJwtDecoder = mock(ReactiveJwtDecoder::class.java)
    }

    @Configuration
    class ConverterConfiguration {
        @Bean
        fun converter(): Converter<Jwt, Mono<AbstractAuthenticationToken>> = Converter { Mono.empty() }
    }
}
