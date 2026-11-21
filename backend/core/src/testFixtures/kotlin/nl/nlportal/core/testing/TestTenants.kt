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
package nl.nlportal.core.testing

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.GeheimVerwijzing
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.Omgevingstype
import nl.nlportal.core.tenancy.Sessie
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppeling
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.tenancy.Thema
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.util.context.Context

/**
 * Testhulp voor module-tests (epic #499): een tenant in de context, tenants met koppelingen en modulegegevens, en een register
 * dat daaruit gebouwd is zoals de runtime het bouwt. Gebruik in een module met
 * `testImplementation(testFixtures(project(":core")))`.
 */
object TestTenants {
    val BOSRAND = Oin("00000099000000000001")
    val WIJDE_VELD = Oin("00000099000000000002")

    /** Elk geheim lost op naar een lange testwaarde (lang genoeg voor een ZGW-JWT-sleutel); gebruik [geheimen] voor eigen waarden. */
    val ALLE_GEHEIMEN = GeheimenBron { "testwaarde-van-minstens-32-tekens-voor-${it.naam}" }

    /** De verwijzing naar het geheim [naam] van tenant [oin]: elk geheim valt onder de naamruimte van de OIN (`<oin>.<naam>`, ADR 0021 besluit 4). */
    fun geheim(
        naam: String,
        oin: Oin = BOSRAND,
    ) = GeheimVerwijzing("${oin.value}.$naam")

    /** Een secret store met precies deze geheimen, op verwijzing (zie [geheim]). */
    fun geheimen(vararg waarden: Pair<GeheimVerwijzing, String>): GeheimenBron {
        val perNaam = waarden.associate { (verwijzing, waarde) -> verwijzing.naam to waarde }
        return GeheimenBron { perNaam[it.naam] }
    }

    /** Een tenant met de standaardwaarden van een testomgeving; [domeinen] is standaard `<oin>.example`. */
    fun tenant(
        oin: Oin = BOSRAND,
        koppelingen: Map<String, TenantKoppeling> = emptyMap(),
        modules: Map<String, Any> = emptyMap(),
        domeinen: List<String> = listOf("${oin.value}.example"),
    ) = TenantDefinition(
        oin = oin,
        naam = "Gemeente ${oin.value}",
        omgevingstype = Omgevingstype.TEST,
        domeinen = domeinen,
        thema = Thema(titel = "Test"),
        inlogmiddelen = setOf(Inlogmiddel.TEST),
        minimumNiveau = Betrouwbaarheidsniveau.SUBSTANTIEEL,
        sessie = Sessie(),
        koppelingen = koppelingen,
        modules = modules,
    )

    /** Een koppeling naar [url] met een API-sleutel uit het geheim `<oin>.test-sleutel`; geef [auth] mee voor een ander type. */
    fun koppeling(
        url: String,
        auth: TenantKoppelingAuth? = null,
        leverancier: String? = null,
        versie: String? = null,
        oin: Oin = BOSRAND,
    ) = TenantKoppeling(
        url,
        auth ?: TenantKoppelingAuth(type = TenantKoppelingAuthType.API_KEY, headerNaam = "X-Api-Key", waarde = geheim("test-sleutel", oin)),
        leverancier,
        versie,
    )

    /** Een koppeling naar [url] met ZGW-JWT: het geheim is `<oin>.<geheimnaam>` (minstens 32 tekens in [ALLE_GEHEIMEN]). */
    fun zgwKoppeling(
        url: String,
        clientId: String,
        geheimnaam: String = "zaken-secret",
        oin: Oin = BOSRAND,
    ) = koppeling(url, TenantKoppelingAuth(TenantKoppelingAuthType.ZGW_JWT, clientId = clientId, secret = geheim(geheimnaam, oin)), oin = oin)

    fun directory(vararg tenants: TenantDefinition) = TenantDirectory(tenants.toList())

    /**
     * Het register van [tenants], gebouwd langs dezelfde route als de runtime ([KoppelingRegister.bouwUitTenants]): ook de regels voor
     * koppelingen, de naamruimte en de aanwezigheid van elk geheim (ook in `modules`) gelden, zodat een moduletest geen tenant kan
     * bouwen die het opstarten zou weigeren. De geheimen komen uit [geheimen].
     */
    fun register(
        vararg tenants: TenantDefinition,
        geheimen: GeheimenBron = ALLE_GEHEIMEN,
        webClientBuilder: WebClient.Builder = WebClient.builder(),
    ): KoppelingRegister = KoppelingRegister.bouwUitTenants(tenants.toList(), geheimen, webClientBuilder = webClientBuilder)

    /** Draait [blok] met [oin] als tenant in de coroutinecontext, zoals de tenantresolutie dat voor een request doet. */
    fun <T> metTenant(
        oin: Oin = BOSRAND,
        blok: suspend () -> T,
    ): T = runBlocking(ReactorContext(tenantContext(oin))) { blok() }

    /** Als [metTenant], vanuit een `suspend`-test. */
    suspend fun <T> alsTenant(
        oin: Oin = BOSRAND,
        blok: suspend () -> T,
    ): T = withContext(ReactorContext(tenantContext(oin))) { blok() }

    /** De Reactor Context met [oin] als tenant, voor `Mono`-code: `mono.contextWrite(TestTenants.tenantContext(oin))`. */
    fun tenantContext(oin: Oin = BOSRAND): Context = Context.of(TenantContext.SLEUTEL, oin)
}

/** Zet [oin] als tenant op elk request, voor module-ITs die zonder tenantresolutie draaien (registreer als bean). */
class VasteTenantWebFilter(
    private val oin: Oin = TestTenants.BOSRAND,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> = chain.filter(exchange).contextWrite(TestTenants.tenantContext(oin))
}