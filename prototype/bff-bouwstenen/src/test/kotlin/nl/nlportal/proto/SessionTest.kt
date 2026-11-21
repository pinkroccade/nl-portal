package nl.nlportal.proto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64

/** Sessie-ID, fixatie, absolute timeout met gestuurde klok, tenantbinding en wat er in Redis staat. */
class SessionTest : BffTestBase() {

    @Test
    fun `het sessie-ID heeft minstens 128 bit en is uniek`() {
        val ids = (1..20).map { login(bosrand).browser.jar.getValue(SESSION_COOKIE) }
        assertThat(ids.toSet()).hasSize(20)
        ids.forEach { id ->
            assertThat(id).matches("[A-Za-z0-9_-]+")
            assertThat(Base64.getUrlDecoder().decode(id).size).isGreaterThanOrEqualTo(16)
            assertThat(id).hasSize(43).doesNotMatch("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        }
    }

    @Test
    fun `een door de aanvaller gekozen sessie-ID wordt bij inloggen niet overgenomen`() {
        val b = Browser(bosrand.host).also { it.jar[SESSION_COOKIE] = "aanvaller-kiest-dit-id" }
        val result = login(bosrand, b)
        assertThat(result.browser.jar.getValue(SESSION_COOKIE)).isNotEqualTo("aanvaller-kiest-dit-id")
        val attacker = Browser(bosrand.host).also { it.jar[SESSION_COOKIE] = "aanvaller-kiest-dit-id" }
        assertThat(get(attacker, "/api/session").status.value()).isEqualTo(401)
    }

    @Test
    fun `de absolute timeout volgt de injecteerbare klok`() {
        val b = login(bosrand).browser
        clock.advance(Duration.ofHours(8).minusSeconds(1))
        assertThat(get(b, "/api/session").status.value()).isEqualTo(200)
        clock.advance(Duration.ofSeconds(2))
        val expired = get(b, "/api/session")
        assertThat(expired.status.value()).isEqualTo(401)
        // het sessiecookie wordt verwijderd en de sessie bestaat niet meer
        assertThat(expired.responseHeaders["Set-Cookie"].orEmpty().joinToString()).contains("$SESSION_COOKIE=").contains("Max-Age=0")
        assertThat(redis.keys("spring:session:sessions:*").collectList().block()).isEmpty()
    }

    @Test
    fun `een sessie van de ene tenant faalt op de andere tenant`() {
        val b = login(bosrand).browser
        val wrongHost = Browser(wijdeveld.host).also { it.jar[SESSION_COOKIE] = b.jar.getValue(SESSION_COOKIE) }
        assertThat(get(wrongHost, "/api/session").status.value()).isEqualTo(401)
        // bij een mismatch wordt de sessie ook ongeldig op de eigen tenant
        assertThat(get(b, "/api/session").status.value()).isEqualTo(401)
    }

    @Test
    fun `in Redis staat geen access- of refreshtoken, alleen het ID-token`() {
        login(bosrand)
        val values = redis.keys("spring:session:sessions:*").flatMap { key ->
            redis.type(key).flatMapMany {
                if (it.code() == "hash") redis.opsForHash<String, String>().entries(key).map { e -> "${e.key}=${e.value}" }
                else redis.opsForValue().get(key).flux()
            }
        }.collectList().block()!!.joinToString("\n")
        assertThat(values).contains("nlp.tenant")
        // een JWT begint met eyJ: het ID-token is er een, het access-token mag er niet bij staan
        assertThat(Regex("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").findAll(values).count()).isEqualTo(1)
    }
}
