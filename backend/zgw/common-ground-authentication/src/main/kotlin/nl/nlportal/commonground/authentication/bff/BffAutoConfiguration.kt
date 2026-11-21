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

import nl.nlportal.core.security.config.HttpSecurityConfigurer
import nl.nlportal.core.tenancy.TenancyAutoConfiguration
import nl.nlportal.core.tenancy.SLEUTELMAP
import nl.nlportal.core.tenancy.TenantDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
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
    afterName = ["org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration"],
)
@ConditionalOnBean(TenantDirectory::class)
@Import(BffRedisConfiguration::class)
class BffAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun bffClock(): Clock = Clock.systemUTC()

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
        val sleutelmap = Path.of(environment.getRequiredProperty(SLEUTELMAP))
        return TenantClientRegistraties(tenants.tenants, TenantClientSleutels.laad(tenants.tenants, sleutelmap), indeling.getIfAvailable { KeycloakEindpunten })
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
            addCookieInitializer {
                it
                    .httpOnly(true)
                    .secure(true)
                    .path("/")
                    .sameSite("Strict")
            }
        }

    private companion object {
        val FLOW_LEVENSDUUR: Duration = Duration.ofMinutes(5)
    }
}