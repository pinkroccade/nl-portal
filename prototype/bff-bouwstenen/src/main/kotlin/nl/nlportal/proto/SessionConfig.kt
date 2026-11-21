package nl.nlportal.proto

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.SessionIdGenerator
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer
import org.springframework.session.data.redis.ReactiveRedisSessionRepository
import org.springframework.web.server.session.CookieWebSessionIdResolver
import org.springframework.web.server.session.WebSessionIdResolver
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

const val SESSION_COOKIE = "__Host-Http-NLP"

/** 256 bit uit een `SecureRandom`; Spring Session gebruikt standaard een UUID (122 bit, waarvan 6 vast). */
class SecureSessionIdGenerator : SessionIdGenerator {
    private val random = SecureRandom()

    override fun generate(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
}

@Configuration
class SessionConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun webSessionIdResolver(): WebSessionIdResolver = CookieWebSessionIdResolver().apply {
        cookieName = SESSION_COOKIE
        addCookieInitializer { it.httpOnly(true).secure(true).path("/").sameSite("Strict") }
    }

    @Bean
    fun sessionIdGeneratorCustomizer(): ReactiveSessionRepositoryCustomizer<ReactiveRedisSessionRepository> =
        ReactiveSessionRepositoryCustomizer { it.setSessionIdGenerator(SecureSessionIdGenerator()) }
}
