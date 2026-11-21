package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.security.config.HttpSecurityConfigurer
import nl.nlportal.core.tenancy.TenancyAutoConfiguration
import nl.nlportal.core.tenancy.TenantDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer
import org.springframework.session.data.redis.ReactiveRedisSessionRepository
import org.springframework.web.server.session.CookieWebSessionIdResolver
import org.springframework.web.server.session.WebSessionIdResolver
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * Het inloggen van de burger: de backend is de backend-for-frontend (ADR 0022, epic #499). Staat alleen aan met tenancy (de
 * client-registratie komt uit het tenantbestand); zonder tenancy is er geen login en blijft alles buiten de uitzonderingen
 * onbereikbaar.
 *
 * Runtime-brede configuratie: `nl-portal.authentication.broker-url` (de publieke basis-URL van Keycloak; elke issuer valt
 * eronder) en de Redis-verbinding (`spring.data.redis.*`, met ACL, TLS buiten lokaal en zonder persistentie).
 */
@AutoConfiguration(
    after = [TenancyAutoConfiguration::class],
    beforeName = ["org.springframework.boot.webflux.autoconfigure.WebSessionIdResolverAutoConfiguration"],
    afterName = ["org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration"])
@ConditionalOnBean(TenantDirectory::class)
class BffAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun bffClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean(EenmaligeOpslag::class)
    fun eenmaligeOpslag(redis: ReactiveStringRedisTemplate): EenmaligeOpslag = RedisEenmaligeOpslag(redis)

    @Bean
    @ConditionalOnMissingBean(Beveiligingsgebeurtenissen::class)
    fun beveiligingsgebeurtenissen(): Beveiligingsgebeurtenissen = LogBeveiligingsgebeurtenissen()

    @Bean
    fun tenantClientRegistraties(
        tenants: TenantDirectory,
        environment: Environment,
        indeling: ObjectProvider<IdpEindpuntenIndeling>,
    ): TenantClientRegistraties {
        BrokerBasis.controleer(tenants.tenants, environment.getRequiredProperty("nl-portal.authentication.broker-url"))
        val basisMap = environment.getProperty("nl-portal.tenancy.directory")?.let { Path.of(it) }
        return TenantClientRegistraties(tenants.tenants, TenantClientSleutels.laad(tenants.tenants, basisMap), indeling.getIfAvailable { KeycloakEindpunten })
    }

    @Bean
    fun preAutorisatieRepository(opslag: EenmaligeOpslag) = PreAutorisatieRepository(opslag, FLOW_LEVENSDUUR)

    @Bean
    fun sessieSecurityContextRepository(
        clock: Clock,
        tenants: TenantDirectory,
    ) = SessieSecurityContextRepository(clock, tenants)

    @Bean
    fun tenantOriginFilter(tenants: TenantDirectory) = TenantOriginFilter(tenants)

    @Bean
    fun originRegelFilter(
        tenants: TenantDirectory,
        gebeurtenissen: Beveiligingsgebeurtenissen,
    ) = OriginRegelFilter(tenants, gebeurtenissen)

    @Bean
    fun registratiePadFilter(
        tenants: TenantDirectory,
        preAuth: PreAutorisatieRepository,
        gebeurtenissen: Beveiligingsgebeurtenissen,
    ) = RegistratiePadFilter(tenants, preAuth, gebeurtenissen)

    @Bean
    fun sessieBewakingFilter(
        clock: Clock,
        tenants: TenantDirectory,
        gebeurtenissen: Beveiligingsgebeurtenissen,
    ) = SessieBewakingFilter(clock, tenants, gebeurtenissen)

    @Bean
    fun bffSecurityConfigurer(
        registraties: TenantClientRegistraties,
        preAuth: PreAutorisatieRepository,
        contextRepository: SessieSecurityContextRepository,
        tenants: TenantDirectory,
        clock: Clock,
        gebeurtenissen: Beveiligingsgebeurtenissen,
    ): HttpSecurityConfigurer {
        val uitlog = UitlogHandler(registraties, tenants, gebeurtenissen)
        return BffSecurityConfigurer(
            registraties = registraties,
            preAuth = preAuth,
            startResolver = InlogStartResolver(registraties, tenants, clock, gebeurtenissen),
            manager = BffAuthenticationManager.voor(registraties, tenants),
            contextRepository = contextRepository,
            succes = InlogSuccesHandler(gebeurtenissen),
            falen = InlogFalenHandler(gebeurtenissen),
            uitlog = uitlog,
            gebeurtenissen = gebeurtenissen,
        )
    }

    @Bean
    fun sessieController(tenants: TenantDirectory) = SessieController(tenants)

    /** De sessiecookie: `__Host-Http-`, `HttpOnly`, `Secure`, `Path=/`, geen `Domain`, `SameSite=Strict`. */
    @Bean
    fun bffWebSessionIdResolver(): WebSessionIdResolver =
        CookieWebSessionIdResolver().apply {
            cookieName = BffNamen.SESSIE_COOKIE
            addCookieInitializer { it.httpOnly(true).secure(true).path("/").sameSite("Strict") }
        }

    /** Sessie-ID's van 256 bit uit een eigen generator (Spring Session gebruikt standaard een UUID van 122 bit). */
    @Bean
    @ConditionalOnClass(ReactiveRedisSessionRepository::class)
    fun sessieIdGeneratorCustomizer(): ReactiveSessionRepositoryCustomizer<ReactiveRedisSessionRepository> =
        ReactiveSessionRepositoryCustomizer { it.setSessionIdGenerator(SecureSessionIdGenerator()) }

    private companion object {
        val FLOW_LEVENSDUUR: Duration = Duration.ofMinutes(5)
    }
}
