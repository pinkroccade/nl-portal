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
package nl.nlportal.core.koppeling

import kotlinx.coroutines.runBlocking
import nl.nlportal.core.tenancy.GeheimVerwijzing
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.OmgevingGeheimenBron
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.testing.VasteTenantWebFilter
import nl.nlportal.core.util.ModuleNotConfiguredException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import java.util.concurrent.atomic.AtomicReference
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import nl.nlportal.core.tenancy.TenantContext

/** Seam: het register leest per tenant uit `TenantDefinition`, met geheimen als verwijzing (epic #499, story 11, 12, 14). */
class TenantKoppelingenTest {
    private val bosrand = TestTenants.BOSRAND
    private val wijdeVeld = TestTenants.WIJDE_VELD

    private lateinit var bosrandBron: MockWebServer
    private lateinit var wijdeVeldBron: MockWebServer

    @BeforeEach
    fun start() {
        bosrandBron = MockWebServer().apply { start() }
        wijdeVeldBron = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        listOf(bosrandBron, wijdeVeldBron).forEach { runCatching { it.shutdown() } }
    }

    private fun url(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private data class Zaak(
        val id: String,
    )

    @Test
    fun `elke tenant krijgt zijn eigen koppeling en zijn eigen geheim, nooit dat van een andere tenant`() {
        bosrandBron.enqueue(json("""{"id":"bosrand"}"""))
        wijdeVeldBron.enqueue(json("""{"id":"wijdeveld"}"""))
        val register =
            TestTenants.register(
                TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron), TestTenants.koppeling("x").auth.copy(waarde = GeheimVerwijzing("bosrand-sleutel"))))),
                TestTenants.tenant(wijdeVeld, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(wijdeVeldBron), TestTenants.koppeling("x").auth.copy(waarde = GeheimVerwijzing("wijdeveld-sleutel"))))),
                geheimen = TestTenants.geheimen("bosrand-sleutel" to "geheim-van-bosrand", "wijdeveld-sleutel" to "geheim-van-wijdeveld"),
            )

        val zaakBosrand = TestTenants.metTenant(bosrand) { register.current("zaken").get<Zaak>("/zaken/1") }
        val zaakWijdeVeld = TestTenants.metTenant(wijdeVeld) { register.current("zaken").get<Zaak>("/zaken/1") }

        assertThat(zaakBosrand).isEqualTo(Zaak("bosrand"))
        assertThat(zaakWijdeVeld).isEqualTo(Zaak("wijdeveld"))
        assertThat(bosrandBron.takeRequest().getHeader("X-Api-Key")).isEqualTo("geheim-van-bosrand")
        assertThat(wijdeVeldBron.takeRequest().getHeader("X-Api-Key")).isEqualTo("geheim-van-wijdeveld")
        assertThat(bosrandBron.requestCount).isEqualTo(1)
        assertThat(wijdeVeldBron.requestCount).isEqualTo(1)
    }

    @Test
    fun `een tenant zonder deze koppeling krijgt een fout en niet de koppeling van een andere tenant`() {
        val register = TestTenants.register(TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron)))), TestTenants.tenant(wijdeVeld))

        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(wijdeVeld) { register.current("zaken") } }
        assertThrows<TenantContextMissingException> { runBlocking { register.current("zaken") } }
    }

    @Test
    fun `elk auth type uit het tenantbestand wordt een werkende koppeling`() {
        val alle =
            mapOf(
                "bearer" to TenantKoppelingAuth(TenantKoppelingAuthType.BEARER, token = GeheimVerwijzing("t")),
                "zgwjwt" to TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "client", secret = GeheimVerwijzing("s")),
                "cc" to TenantKoppelingAuth(TenantKoppelingAuthType.OAUTH2_CLIENT_CREDENTIALS, clientId = "client", secret = GeheimVerwijzing("s"), tokenUrl = url(bosrandBron) + "/token"),
            )
        val register = TestTenants.register(TestTenants.tenant(bosrand, koppelingen = alle.mapValues { TestTenants.koppeling(url(bosrandBron), it.value, leverancier = "Open Zaak", versie = "1.31") }))

        TestTenants.metTenant(bosrand) { alle.keys.forEach { assertThat(register.current(it)).isNotNull } }
    }

    @Test
    fun `een geheim dat ontbreekt in de secret store weigert het opstarten, met de OIN en de naam van het geheim`() {
        val fout =
            assertThrows<IllegalStateException> {
                TestTenants.register(
                    TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron)))),
                    geheimen = GeheimenBron { null },
                )
            }

        assertThat(fout.message).isEqualTo("Tenant ${bosrand.value}: koppeling 'zaken': Geheim 'test-sleutel' staat niet in de secret store")
    }

    @Test
    fun `een leeg geheim telt als ontbrekend`() {
        assertThrows<IllegalStateException> {
            TestTenants.register(TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron)))), geheimen = GeheimenBron { "  " })
        }
    }

    @Test
    fun `een koppeling die niet te bouwen is weigert het opstarten met de OIN, ook als het type zijn velden mist`() {
        val zonderSecret = TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = "client")
        val onvolledig = assertThrows<IllegalStateException> { TestTenants.register(TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron), zonderSecret)))) }
        val ongeldigeUrl = assertThrows<IllegalStateException> { TestTenants.register(TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling("geen url")))) }

        assertThat(onvolledig.message).startsWith("Tenant ${bosrand.value}: koppeling 'zaken' is onvolledig")
        assertThat(ongeldigeUrl.message).startsWith("Tenant ${bosrand.value}: koppeling 'zaken' is ongeldig")
    }

    @Test
    fun `een koppeling uit het tenantbestand wint van dezelfde naam in nl-portal-koppelingen, de rest van de tussenfase blijft`() {
        bosrandBron.enqueue(json("""{"id":"uit-tenantbestand"}"""))
        wijdeVeldBron.enqueue(json("""{"id":"oud"}"""))
        val oud =
            Koppeling().apply {
                url = url(wijdeVeldBron)
                auth.type = KoppelingAuthType.BEARER
                auth.token = "oud"
            }
        val register =
            KoppelingRegister.bouw(
                mapOf(bosrand to mapOf("zaken" to oud, "brp" to oud)),
                WebClient.builder(),
                gemigreerd = mapOf(bosrand to mapOf("zaken" to TestTenants.koppeling(url(bosrandBron)).naarKoppeling(bosrand, "zaken", TestTenants.ALLE_GEHEIMEN))),
            )

        val zaak = TestTenants.metTenant(bosrand) { register.current("zaken").get<Zaak>("/z") }
        val brp = TestTenants.metTenant(bosrand) { register.current("brp").get<Zaak>("/z") }

        assertThat(zaak).isEqualTo(Zaak("uit-tenantbestand"))
        assertThat(brp).isEqualTo(Zaak("oud"))
    }

    @Test
    fun `de autoconfiguratie bouwt het register uit de tenants met geheimen uit de omgeving`() {
        bosrandBron.enqueue(json("""{"id":"z"}"""))
        val tenant = TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron))))
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoppelingAutoConfiguration::class.java))
            .withBean(WebClient.Builder::class.java, { WebClient.builder() })
            .withBean(ZgwJwtGenerator::class.java, { ZgwJwtGenerator() })
            .withBean(TenantDirectory::class.java, { TenantDirectory(listOf(tenant)) })
            .withPropertyValues("nl-portal.geheimen.test-sleutel=uit-de-omgeving")
            .run { context ->
                assertThat(context).hasNotFailed()
                TestTenants.metTenant(bosrand) { context.getBean(KoppelingRegister::class.java).current("zaken").get<Zaak>("/z") }
                assertThat(bosrandBron.takeRequest().getHeader("X-Api-Key")).isEqualTo("uit-de-omgeving")
            }
    }

    @Test
    fun `de autoconfiguratie gebruikt een eigen geheimenbron als die er is`() {
        val tenant = TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron))))
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoppelingAutoConfiguration::class.java))
            .withBean(WebClient.Builder::class.java, { WebClient.builder() })
            .withBean(ZgwJwtGenerator::class.java, { ZgwJwtGenerator() })
            .withBean(TenantDirectory::class.java, { TenantDirectory(listOf(tenant)) })
            .withBean(GeheimenBron::class.java, { GeheimenBron { "eigen" } })
            .run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `de autoconfiguratie weigert het opstarten als een geheim ontbreekt, met de OIN in de melding`() {
        val tenant = TestTenants.tenant(bosrand, koppelingen = mapOf("zaken" to TestTenants.koppeling(url(bosrandBron))))
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoppelingAutoConfiguration::class.java))
            .withBean(WebClient.Builder::class.java, { WebClient.builder() })
            .withBean(ZgwJwtGenerator::class.java, { ZgwJwtGenerator() })
            .withBean(TenantDirectory::class.java, { TenantDirectory(listOf(tenant)) })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString()).contains("Tenant ${bosrand.value}: koppeling 'zaken'")
            }
    }

    @Test
    fun `de geheimenbron uit de omgeving leest nl-portal-geheimen en geeft null voor wat er niet is`() {
        val omgeving = MockEnvironment().withProperty("nl-portal.geheimen.zaken-sleutel", "waarde")
        val bron = OmgevingGeheimenBron(omgeving)

        assertThat(bron.resolve(GeheimVerwijzing("zaken-sleutel"))).isEqualTo("waarde")
        assertThat(bron.resolve(GeheimVerwijzing("anders"))).isNull()
        assertThat(bron.vereis(GeheimVerwijzing("zaken-sleutel"))).isEqualTo("waarde")
    }

    // --- de testhulp zelf ---

    @Test
    fun `de testhulp zet de tenant in de context voor suspend- en Mono-code`() {
        val viaMono = TenantContext.currentMono().contextWrite(TestTenants.tenantContext(wijdeVeld)).block()
        val viaSuspend = TestTenants.metTenant(wijdeVeld) { TenantContext.current() }
        val standaard = TestTenants.metTenant { TenantContext.current() }
        val viaAlsTenant = runBlocking { TestTenants.alsTenant(bosrand) { TenantContext.current() } }

        assertThat(viaMono).isEqualTo(wijdeVeld)
        assertThat(viaSuspend).isEqualTo(wijdeVeld)
        assertThat(standaard).isEqualTo(bosrand)
        assertThat(viaAlsTenant).isEqualTo(bosrand)
    }

    @Test
    fun `de testhulp levert tenants, een directory en een filter met een vaste tenant`() {
        val tenant = TestTenants.tenant(wijdeVeld)
        val directory = TestTenants.directory(TestTenants.tenant(bosrand), tenant)
        val exchange: ServerWebExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build())
        var gezien: Oin? = null

        VasteTenantWebFilter(wijdeVeld).filter(exchange) { TenantContext.currentMono().doOnNext { gezien = it }.then() }.block()
        val standaard = AtomicReference<Oin>()
        VasteTenantWebFilter().filter(exchange) { TenantContext.currentMono().doOnNext { standaard.set(it) }.then() }.block()

        assertThat(tenant.domeinen).containsExactly("${wijdeVeld.value}.example")
        assertThat(directory.forHost("${wijdeVeld.value}.example")).isSameAs(tenant)
        assertThat(gezien).isEqualTo(wijdeVeld)
        assertThat(standaard.get()).isEqualTo(bosrand)
    }

    @Test
    fun `een tenant uit de testhulp levert zijn modulegegevens via TenantDirectory`() {
        data class Instelling(
            val naam: String,
        )
        val directory = TestTenants.directory(TestTenants.tenant(bosrand, modules = mapOf("proef" to Instelling("bosrand"))), TestTenants.tenant(wijdeVeld))

        val gelezen = TestTenants.metTenant(bosrand) { directory.module<Instelling>() }

        assertThat(gelezen).isEqualTo(Instelling("bosrand"))
        assertThrows<ModuleNotConfiguredException> { TestTenants.metTenant(wijdeVeld) { directory.module<Instelling>() } }
        assertThrows<TenantContextMissingException> { runBlocking { directory.module<Instelling>() } }
        assertThrows<TenantContextMissingException> { TestTenants.metTenant(Oin("00000099000000000009")) { directory.module<Instelling>() } }
    }
}