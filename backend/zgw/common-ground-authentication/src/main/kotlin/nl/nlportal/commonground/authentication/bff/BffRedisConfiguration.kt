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

import io.lettuce.core.ClientOptions
import io.lettuce.core.MaintNotificationsConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer
import org.springframework.session.data.redis.ReactiveRedisSessionRepository

/** Alles van de BFF dat Redis raakt: de pre-auth-opslag, het sessie-ID en de serialisatie van de sessie. */
@Configuration(proxyBeanMethods = false)
class BffRedisConfiguration {
    @Bean
    @ConditionalOnMissingBean(EenmaligeOpslag::class)
    fun eenmaligeOpslag(redis: ReactiveStringRedisTemplate): EenmaligeOpslag = RedisEenmaligeOpslag(redis)

    /** Sessie-ID's van 256 bit uit een eigen generator (Spring Session gebruikt standaard een UUID van 122 bit). */
    @Bean
    @ConditionalOnClass(ReactiveRedisSessionRepository::class)
    fun sessieIdGeneratorCustomizer(): ReactiveSessionRepositoryCustomizer<ReactiveRedisSessionRepository> = ReactiveSessionRepositoryCustomizer { it.setSessionIdGenerator(SecureSessionIdGenerator()) }

    /** Spring Session pakt een bean met deze naam op als serializer van de sessie (standaard: Java-serialisatie). */
    @Bean("springSessionDefaultRedisSerializer")
    fun sessieSerializer(): RedisSerializer<Any> = SessieSerializer()

    /** Lettuce meldt zich met `CLIENT MAINT_NOTIFICATIONS`; Redis 8.2 kent dat niet en Lettuce logt er dan een stacktrace voor. */
    @Bean
    fun geenOnderhoudsmeldingen(): LettuceClientConfigurationBuilderCustomizer =
        LettuceClientConfigurationBuilderCustomizer {
            it.clientOptions(ClientOptions.builder().maintNotificationsConfig(MaintNotificationsConfig.disabled()).build())
        }
}