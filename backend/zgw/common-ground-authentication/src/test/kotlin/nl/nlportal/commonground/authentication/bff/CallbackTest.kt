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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLEncoder
import java.time.Duration
import java.util.stream.Stream

/** De negatieve tests van de callback: replay, vreemde flow, issuer (RFC 9207), inlogmiddel, niveau, vertegenwoordiging en BSN. */
class CallbackTest : BffTestBasis() {
    private val bosrand = BffTestOmgeving.bosrand
    private val wijdeveld = BffTestOmgeving.wijdeveld

    private fun assertGeweigerd(
        inlog: Inlog,
        reden: Reden,
        tenant: TestTenant,
    ) {
        assertThat(inlog.callback.status.value()).isEqualTo(302)
        assertThat(
            inlog.callback.responseHeaders.location
                .toString(),
        ).isEqualTo("/?inloggen=mislukt")
        assertThat(inlog.browser.jar.keys).doesNotContain(BffNamen.SESSIE_COOKIE, BffNamen.FLOW_COOKIE)
        val event = BffTestOmgeving.gebeurtenissen.single { it.type == GebeurtenisType.LOGIN_GEWEIGERD }
        assertThat(event.reden).isEqualTo(reden)
        assertThat(event.oin).isEqualTo(tenant.oin.value)
        assertThat(get(inlog.browser, "/api/test/wie").status.value()).isEqualTo(401)
        val regel = beveiligingsregels.single { "type=login_geweigerd" in it }
        assertThat(regel).contains("oin=${tenant.oin}", "reden=${reden.code}", "uitkomst=geweigerd")
        assertGeenBsnInLog()
    }

    /** Nergens in de log (de weigerregel en alle andere regels van de app) staat een BSN, een NameID met sectorprefix of een token. */
    private fun assertGeenBsnInLog() {
        val alles = logregels.joinToString(" | ")
        assertThat(alles).doesNotContain("999993653", "999993847", "123456789", "99999365", "s00000000", "eyJ")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weigeringen")
    fun `een login die bij de callback niet voldoet wordt geweigerd met een securityevent`(
        naam: String,
        tenantNaam: String,
        spec: TokenSpec,
        reden: Reden,
    ) {
        val tenant = BffTestOmgeving.tenants.single { it.naam == tenantNaam }

        val inlog = log(tenant, spec)

        assertGeweigerd(inlog, reden, tenant)
    }

    @ParameterizedTest
    @ValueSource(strings = [TokenSpec.DIGID_SUBSTANTIEEL, TokenSpec.DIGID_HOOG])
    fun `een niveau op of boven het minimum van de tenant wordt toegelaten`(acr: String) {
        slaagt(log(wijdeveld, TokenSpec(acr = acr)))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("onjuisteTokenHerkomst")
    fun `een ID-token van een andere realm of voor een andere client wordt geweigerd en geeft een event met OIN en actor`(
        naam: String,
        spec: TokenSpec,
        reden: Reden,
    ) {
        val inlog = log(bosrand, spec)

        assertGeweigerd(inlog, reden, bosrand)
        assertThat(
            BffTestOmgeving.gebeurtenissen
                .single { it.type == GebeurtenisType.LOGIN_GEWEIGERD }
                .actor
                ?.subject,
        ).describedAs(naam).isIn("gebruiker-1", null)
    }

    @Test
    fun `een geslaagde login geeft een login_geslaagd-event met OIN en actor en zonder BSN in de log`() {
        val inlog = log(wijdeveld, TokenSpec(subject = "actor-7", acr = TokenSpec.DIGID_SUBSTANTIEEL))
        slaagt(inlog)

        val event = BffTestOmgeving.gebeurtenissen.single { it.type == GebeurtenisType.LOGIN_GESLAAGD }
        assertThat(event.oin).isEqualTo(wijdeveld.oin.value)
        assertThat(event.actor).isEqualTo(Actor(wijdeveld.issuer, "actor-7"))
        assertThat(event.uitkomst).isEqualTo("geslaagd")
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.type }).doesNotContain(GebeurtenisType.LOGIN_GEWEIGERD)
        val regel = beveiligingsregels.single()
        assertThat(regel).startsWith("beveiligingsevent type=login_geslaagd oin=${wijdeveld.oin} actor=${wijdeveld.issuer}|actor-7 uitkomst=geslaagd reden=geen correlatie=")
        assertGeenBsnInLog()
    }

    @Test
    fun `de securityevents bevatten OIN en actor maar nooit het BSN`() {
        log(wijdeveld, TokenSpec(subject = "actor-1", acr = TokenSpec.DIGID_BASIS))

        val event = BffTestOmgeving.gebeurtenissen.single()
        assertThat(event.actor).isEqualTo(Actor(wijdeveld.issuer, "actor-1"))
        assertThat(event.toString()).doesNotContain("999993653")
    }

    @Test
    fun `een replay van dezelfde callback faalt en levert geen sessie`() {
        bosrand.tokens.spec = TokenSpec()
        val browser = Browser(bosrand)
        val start = get(browser, "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=digid")
        val flowCookie = browser.jar.getValue(BffNamen.FLOW_COOKIE)
        val terug = idpAutoriseer(start.responseHeaders.location!!, bosrand.tokens.spec.subject)
        slaagt(Inlog(browser, start, callback(browser, terug, bosrand.issuer)))

        val herhaling = Browser(bosrand)
        herhaling.jar[BffNamen.FLOW_COOKIE] = flowCookie
        val tweede = callback(herhaling, terug, bosrand.issuer)

        assertThat(tweede.status.value()).isEqualTo(302)
        assertThat(tweede.responseHeaders.location.toString()).isEqualTo("/?inloggen=mislukt")
        assertThat(herhaling.jar.keys).doesNotContain(BffNamen.SESSIE_COOKIE)
    }

    @Test
    fun `een callback zonder flowcookie of met een andere state faalt`() {
        val browser = Browser(bosrand)
        val start = get(browser, "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=digid")
        val terug = idpAutoriseer(start.responseHeaders.location!!)

        val zonderCookie = callback(Browser(bosrand), terug, bosrand.issuer)
        val anderState = callback(browser, java.net.URI.create(terug.toString().replace(Regex("state=[^&]+"), "state=ander")), bosrand.issuer)

        assertThat(zonderCookie.responseHeaders.location.toString()).isEqualTo("/?inloggen=mislukt")
        assertThat(anderState.responseHeaders.location.toString()).isEqualTo("/?inloggen=mislukt")
        assertThat(browser.jar.keys).doesNotContain(BffNamen.SESSIE_COOKIE, BffNamen.FLOW_COOKIE)
    }

    @Test
    fun `een flowcookie van tenant A wordt op tenant B geweigerd en is daarna verbruikt`() {
        val bosrandBrowser = Browser(bosrand)
        val start = get(bosrandBrowser, "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=digid")
        val terug = idpAutoriseer(start.responseHeaders.location!!)
        val wijdeveldBrowser = Browser(wijdeveld)
        wijdeveldBrowser.jar[BffNamen.FLOW_COOKIE] = bosrandBrowser.jar.getValue(BffNamen.FLOW_COOKIE)

        val opAnder = callback(wijdeveldBrowser, java.net.URI.create(terug.toString().replace(bosrand.oin.value, wijdeveld.oin.value)), wijdeveld.issuer)
        val opEigen = callback(bosrandBrowser, terug, bosrand.issuer)

        assertThat(opAnder.responseHeaders.location.toString()).isEqualTo("/?inloggen=mislukt")
        assertThat(wijdeveldBrowser.jar.keys).doesNotContain(BffNamen.SESSIE_COOKIE)
        assertThat(opEigen.responseHeaders.location.toString()).describedAs("de flow is door de poging op tenant B verbruikt").isEqualTo("/?inloggen=mislukt")
    }

    @ParameterizedTest(name = "iss = {0}")
    @MethodSource("onjuisteIssuers")
    fun `een iss-parameter die niet exact de issuer van de tenant is geeft 400 en ruimt de flow op`(
        beschrijving: String,
        iss: String?,
    ) {
        val browser = Browser(bosrand)
        val start = get(browser, "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=digid")
        val terug = idpAutoriseer(start.responseHeaders.location!!)
        val query = terug.rawQuery + (iss?.let { "&iss=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")

        val resultaat = get(browser, terug.rawPath + "?" + query)

        assertThat(resultaat.status.value()).describedAs(beschrijving).isEqualTo(400)
        assertThat(browser.jar.keys).doesNotContain(BffNamen.FLOW_COOKIE, BffNamen.SESSIE_COOKIE)
        assertThat(BffTestOmgeving.gebeurtenissen.map { it.type }).contains(GebeurtenisType.ISSUER_GEWEIGERD)
    }

    @Test
    fun `een dubbele iss-parameter wordt geweigerd`() {
        val browser = Browser(bosrand)
        val start = get(browser, "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=digid")
        val terug = idpAutoriseer(start.responseHeaders.location!!)
        val iss = URLEncoder.encode(bosrand.issuer, Charsets.UTF_8)

        val resultaat = get(browser, terug.rawPath + "?" + terug.rawQuery + "&iss=$iss&iss=$iss")

        assertThat(resultaat.status.value()).isEqualTo(400)
    }

    @Test
    fun `een authorization response met een fout van de IdP is een geweigerde login`() {
        val browser = Browser(bosrand)
        val start = get(browser, "${BffNamen.INLOGPAD}${bosrand.oin}?inlogmiddel=digid")
        val state = Regex("state=([^&]+)").find(start.responseHeaders.location!!.rawQuery)!!.groupValues[1]

        val resultaat = get(browser, "${BffNamen.CALLBACKPAD}${bosrand.oin}?error=access_denied&state=$state&iss=" + URLEncoder.encode(bosrand.issuer, Charsets.UTF_8))

        assertThat(resultaat.status.value()).isEqualTo(302)
        assertThat(resultaat.responseHeaders.location.toString()).isEqualTo("/?inloggen=mislukt")
        assertThat(BffTestOmgeving.gebeurtenissen.single().reden).isEqualTo(Reden.PROTOCOL)
    }

    companion object {
        private val BSN_GOED = "s00000000:999993653"

        @JvmStatic
        fun weigeringen(): Stream<Arguments> =
            Stream.of(
                Arguments.of("te laag niveau Basis", "wijdeveld", TokenSpec(acr = TokenSpec.DIGID_BASIS), Reden.NIVEAU_TE_LAAG),
                Arguments.of("te laag niveau Midden", "wijdeveld", TokenSpec(acr = TokenSpec.DIGID_MIDDEN), Reden.NIVEAU_TE_LAAG),
                Arguments.of("onbekende acr", "wijdeveld", TokenSpec(acr = "urn:onbekend"), Reden.NIVEAU_ONBEKEND),
                Arguments.of("acr is geen tekst", "wijdeveld", TokenSpec(acr = 3), Reden.NIVEAU_ONBEKEND),
                Arguments.of("lege acr geldt als afwezig", "wijdeveld", TokenSpec(acr = ""), Reden.NIVEAU_ONTBREEKT),
                Arguments.of("zonder acr (assertion zonder AuthnContextClassRef)", "wijdeveld", TokenSpec(acr = null), Reden.NIVEAU_ONTBREEKT),
                Arguments.of("uitgeschakeld inlogmiddel in het token, ook als de start een ander middel noemde", "wijdeveld", TokenSpec(inlogmiddel = "test", acr = "Basis"), Reden.INLOGMIDDEL_UIT),
                Arguments.of("wallet staat niet aan voor de tenant", "bosrand", TokenSpec(inlogmiddel = "wallet"), Reden.INLOGMIDDEL_UIT),
                Arguments.of("eherkenning staat niet aan voor de tenant", "bosrand", TokenSpec(inlogmiddel = "eherkenning"), Reden.INLOGMIDDEL_UIT),
                Arguments.of("onbekend inlogmiddel", "bosrand", TokenSpec(inlogmiddel = "onzin"), Reden.INLOGMIDDEL_ONBEKEND),
                Arguments.of("geen inlogmiddel-claim", "bosrand", TokenSpec(inlogmiddel = null), Reden.INLOGMIDDEL_ONBEKEND),
                Arguments.of("LegalSubjectID (vertegenwoordiging)", "bosrand", TokenSpec(extra = mapOf("legal_subject_id" to "s00000000:999993847")), Reden.VERTEGENWOORDIGING),
                Arguments.of("lege LegalSubjectID telt als aanwezig", "bosrand", TokenSpec(extra = mapOf("legal_subject_id" to "")), Reden.VERTEGENWOORDIGING),
                Arguments.of("BSN in het ID-token (realm-drift: het ID-token gaat als id_token_hint de browser in)", "bosrand", TokenSpec(extra = mapOf("acting_subject_id" to BSN_GOED)), Reden.BSN_IN_ID_TOKEN),
                Arguments.of("KvK-identiteit", "bosrand", TokenSpec(extra = mapOf("kvk" to "14127293")), Reden.KVK_IDENTITEIT),
                Arguments.of("BSN met een foute elfproef", "bosrand", TokenSpec(actingSubjectId = "s00000000:123456789"), Reden.BSN_ONGELDIG),
                Arguments.of("BSN zonder sectorprefix bij DigiD", "bosrand", TokenSpec(actingSubjectId = "999993653"), Reden.BSN_ONGELDIG),
                Arguments.of("een andere sectorprefix (pseudoniem)", "bosrand", TokenSpec(actingSubjectId = "s00000001:999993653"), Reden.BSN_ONGELDIG),
                Arguments.of("BSN te kort", "bosrand", TokenSpec(actingSubjectId = "s00000000:99999365"), Reden.BSN_ONGELDIG),
                Arguments.of("BSN met letters", "bosrand", TokenSpec(actingSubjectId = "s00000000:99999365a"), Reden.BSN_ONGELDIG),
                Arguments.of("geen BSN-claim", "bosrand", TokenSpec(actingSubjectId = null), Reden.BSN_ONGELDIG),
                Arguments.of("auth_time ontbreekt", "bosrand", TokenSpec(metAuthTime = false), Reden.AUTH_TIME),
                Arguments.of("auth_time van voor de login (stille hergebruik van een Keycloak-sessie)", "bosrand", TokenSpec(authTimeOffset = Duration.ofMinutes(-30)), Reden.AUTH_TIME),
            )

        @JvmStatic
        fun onjuisteTokenHerkomst(): Stream<Arguments> =
            Stream.of(
                Arguments.of("iss van de realm van een andere tenant", TokenSpec(extra = mapOf("iss" to BffTestOmgeving.wijdeveld.issuer)), Reden.PROTOCOL),
                Arguments.of("iss van een onbekende realm", TokenSpec(extra = mapOf("iss" to "https://onbekend.example/realms/x")), Reden.PROTOCOL),
                Arguments.of("aud van een andere client", TokenSpec(extra = mapOf("aud" to listOf("andere-client"))), Reden.PROTOCOL),
                Arguments.of("een ID-token dat al verlopen is (exp in het verleden)", TokenSpec(geldigheid = Duration.ofHours(-1)), Reden.PROTOCOL),
                Arguments.of("aud met een andere client erbij en een azp van die client", TokenSpec(extra = mapOf("aud" to listOf("nl-portal-backend", "andere-client"), "azp" to "andere-client")), Reden.PROTOCOL),
            )

        @JvmStatic
        fun onjuisteIssuers(): Stream<Arguments> {
            val issuer = BffTestOmgeving.bosrand.issuer
            return Stream.of(
                Arguments.of("ontbreekt", null),
                Arguments.of("andere realm", BffTestOmgeving.wijdeveld.issuer),
                Arguments.of("bijna gelijk met slash", "$issuer/"),
                Arguments.of("hoofdletters", issuer.uppercase()),
                Arguments.of("leeg", ""),
            )
        }
    }
}