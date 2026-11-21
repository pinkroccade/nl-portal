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

import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantFileLoader
import nl.nlportal.core.tenancy.TenantWebFilter
import nl.nlportal.core.tenancy.TrustedProxies
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpCookie
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import org.springframework.web.server.WebSession
import org.springframework.web.server.session.CookieWebSessionIdResolver
import org.springframework.web.server.session.DefaultWebSessionManager
import org.springframework.web.server.session.InMemoryWebSessionStore
import reactor.core.publisher.Mono
import java.net.URLDecoder
import java.nio.file.Path
import java.time.Duration

/**
 * Klokgestuurde unit-tests zonder Spring: de sessiebewaking met de tijden uit het echte demo-tenantbestand, en het foutpad van
 * de uitlog-handler (geen `end_session_endpoint`, kapotte sessie-inhoud, onbekende registratie): de lokale sessie is altijd weg.
 */
class SessieEnUitlogUnitTest {
    private val klok = MutableClock()
    private val gebeurtenissen = mutableListOf<Beveiligingsgebeurtenis>()
    private val vastleggen = Beveiligingsgebeurtenissen { gebeurtenissen += it }
    private val store = InMemoryWebSessionStore().also { it.setClock(klok) }
    private val idResolver = CookieWebSessionIdResolver().also { it.cookieName = BffNamen.SESSIE_COOKIE }
    private val demo: TenantDefinition = TenantFileLoader().load(Path.of("../../../docker-compose/imports/tenants")).tenants.single()

    private fun sessie(
        tenant: TenantDefinition,
        identiteit: String? = identiteitJson(),
        aangemaakt: Long = klok.instant().toEpochMilli(),
    ): WebSession =
        store.createWebSession().block()!!.also {
            it.attributes[SessieAttributen.TENANT] = tenant.oin.value
            it.attributes[SessieAttributen.AANGEMAAKT] = aangemaakt
            it.attributes[SessieAttributen.LAATSTE_ACTIVITEIT] = aangemaakt
            identiteit?.let { json -> it.attributes[SessieAttributen.IDENTITEIT] = json }
            it.maxIdleTime = Duration.ofDays(1)
            it.start()
            it.save().block()
        }

    private fun identiteitJson() = OpgeslagenIdentiteit.serialiseer(OpgeslagenIdentiteit("https://idp.test/realm", "sub-1", "test", "basis", "bsn", "999993653", null, "eyJ.id.token"))

    private fun bestaat(sessie: WebSession) = store.retrieveSession(sessie.id).block() != null

    /** Draait [actie] binnen de tenantresolutie van [tenants] (zoals een echt request), met [sessie] als sessie van het request. */
    private fun metTenant(
        tenants: List<TenantDefinition>,
        host: String,
        sessie: WebSession,
        actie: (ServerWebExchange) -> Mono<Void>,
    ): ServerWebExchange {
        val request =
            MockServerHttpRequest
                .get("http://$host/api/test/wie")
                .header("Host", host)
                .cookie(HttpCookie(BffNamen.SESSIE_COOKIE, sessie.id))
                .build()
        val manager =
            DefaultWebSessionManager().also {
                it.sessionStore = store
                it.sessionIdResolver = idResolver
            }
        val exchange = MockServerWebExchange.builder(request).sessionManager(manager).build()
        TenantWebFilter(TenantDirectory(tenants), TrustedProxies(emptyList())).filter(exchange, WebFilterChain { actie(it) }).block()
        return exchange
    }

    /** Eén request door de sessiebewaking; waar als het request werd doorgelaten en de sessie nog bestaat. */
    private fun bewaak(
        sessie: WebSession,
        tenant: TenantDefinition = demo,
    ): Boolean {
        var doorgelaten = false
        metTenant(listOf(tenant), tenant.domeinen.first(), sessie) { exchange ->
            SessieBewakingFilter(klok, TenantDirectory(listOf(tenant)), vastleggen).filter(exchange) { Mono.fromRunnable { doorgelaten = true } }
        }
        return doorgelaten && bestaat(sessie)
    }

    // --- sessietijden uit het echte demo-tenantbestand ---

    @Test
    fun `het demo-tenantbestand geeft de grenzen van ADR 0022, 15 minuten inactiviteit en 8 uur absoluut`() {
        assertThat(demo.sessie.inactiviteit).isEqualTo(Duration.ofMinutes(15))
        assertThat(demo.sessie.absoluut).isEqualTo(Duration.ofHours(8))
    }

    @Test
    fun `de demo-tenant laat een sessie verlopen na precies de inactiviteitstijd uit het tenantbestand`() {
        val sessie = sessie(demo)
        klok.advance(Duration.ofMinutes(15).minusSeconds(1))
        assertThat(bewaak(sessie)).describedAs("een seconde voor de inactiviteitsgrens").isTrue()

        klok.advance(Duration.ofMinutes(15).minusSeconds(1))
        assertThat(bewaak(sessie)).describedAs("de activiteit van het vorige request schoof het einde op").isTrue()

        klok.advance(Duration.ofMinutes(15))
        assertThat(bewaak(sessie)).describedAs("precies 15 minuten zonder activiteit").isFalse()
        assertThat(gebeurtenissen.single().reden).isEqualTo(Reden.VERLOPEN_INACTIVITEIT)
        assertThat(gebeurtenissen.single().oin).isEqualTo(demo.oin.value)
    }

    @Test
    fun `de demo-tenant laat een sessie verlopen na de absolute duur uit het tenantbestand, ook bij activiteit`() {
        val sessie = sessie(demo)
        klok.advance(Duration.ofHours(8).minusSeconds(1))
        sessie.attributes[SessieAttributen.LAATSTE_ACTIVITEIT] = klok.instant().toEpochMilli()
        assertThat(bewaak(sessie)).describedAs("een seconde voor het absolute einde").isTrue()

        klok.advance(Duration.ofSeconds(1))
        sessie.attributes[SessieAttributen.LAATSTE_ACTIVITEIT] = klok.instant().toEpochMilli()
        assertThat(bewaak(sessie)).describedAs("precies 8 uur, ondanks een actieve sessie").isFalse()
        assertThat(gebeurtenissen.single().reden).isEqualTo(Reden.VERLOPEN_ABSOLUUT)
    }

    // --- uitlogfoutpad ---

    private fun metTestSleutel(tenant: TenantDefinition): TenantDefinition {
        val testTenant = BffTestOmgeving.tenants.first()
        return tenant.copy(authenticatie = tenant.authenticatie.copy(sleutelBestand = testTenant.definitie.authenticatie.sleutelBestand))
    }

    private fun uitlogHandler(
        endSession: String,
        geregistreerd: List<TenantDefinition> = listOf(demo),
    ): UitlogHandler {
        val tenants = geregistreerd.map(::metTestSleutel)
        val sleutels = TenantClientSleutels.laad(tenants, null)
        val registraties = TenantClientRegistraties(tenants, sleutels) { issuer, basis -> IdpEindpunten("$issuer/auth", "$basis/token", "$basis/certs", endSession) }
        return UitlogHandler(registraties, TenantDirectory(listOf(demo)), vastleggen)
    }

    /** Een uitlog-request; controleert dat de lokale sessie weg is en geeft de redirect-URL terug. */
    private fun uitloggen(
        handler: UitlogHandler,
        sessie: WebSession,
    ): String? {
        val auth = TestingAuthenticationToken("x", "y")
        val exchange =
            metTenant(listOf(demo), demo.domeinen.first(), sessie) { ex ->
                val wfe = WebFilterExchange(ex, WebFilterChain { Mono.empty() })
                handler.logout(wfe, auth).then(handler.onLogoutSuccess(wfe, auth))
            }
        assertThat(bestaat(sessie)).describedAs("de lokale sessie is weg").isFalse()
        assertThat(exchange.response.statusCode?.value()).isEqualTo(302)
        return exchange.response.headers.location
            ?.toString()
    }

    @Test
    fun `uitloggen met een volledig end_session_endpoint stuurt naar de IdP met id_token_hint, client en de eigen post_logout_redirect_uri`() {
        val doel = uitloggen(uitlogHandler("https://idp.test/realm/logout"), sessie(demo))!!

        assertThat(doel).startsWith("https://idp.test/realm/logout?id_token_hint=eyJ.id.token&client_id=${demo.authenticatie.clientId}&post_logout_redirect_uri=")
        assertThat(URLDecoder.decode(doel, Charsets.UTF_8)).endsWith("post_logout_redirect_uri=http://${demo.domeinen.first()}/")
        assertThat(gebeurtenissen.map { it.type }).containsExactly(GebeurtenisType.UITGELOGD)
    }

    @Test
    fun `zonder bruikbaar end_session_endpoint gaat de burger naar het portaal en is de lokale sessie weg`() {
        listOf("", "   ", "/relatief/logout", "javascript:alert(1)", "ftp://idp.test/logout").forEach { kapot ->
            val doel = uitloggen(uitlogHandler(kapot), sessie(demo))

            assertThat(doel).describedAs("endpoint [$kapot]").isEqualTo("/")
        }
    }

    @Test
    fun `een sessie met onleesbare identiteit wordt toch beeindigd en stuurt naar het portaal`() {
        assertThat(uitloggen(uitlogHandler("https://idp.test/realm/logout"), sessie(demo, identiteit = "geen-json"))).isEqualTo("/")
    }

    @Test
    fun `een sessie zonder identiteit wordt beeindigd en stuurt naar het portaal`() {
        assertThat(uitloggen(uitlogHandler("https://idp.test/realm/logout"), sessie(demo, identiteit = null))).isEqualTo("/")
    }

    @Test
    fun `een tenant zonder client-registratie laat de sessie toch eindigen`() {
        val handler = uitlogHandler("https://idp.test/realm/logout", geregistreerd = listOf(BffTestOmgeving.wijdeveld.definitie))

        assertThat(uitloggen(handler, sessie(demo))).isEqualTo("/")
    }
}