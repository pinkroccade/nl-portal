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

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.session.ReactiveSessionRepository
import org.springframework.session.Session
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.Date

/**
 * De Redis-kant van de BFF tegen de echte Redis uit de compose (zelfde image, ACL en opstartopdracht): sessie-ID's, TTL op elke
 * sleutel, eenmaligheid over twee app-instanties, de allowlist van de sessieserializer, de ACL en het ontbreken van persistentie.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSessieIT {
    private lateinit var redis: RedisTestInstantie

    @BeforeAll
    fun start() {
        redis = RedisTestInstantie()
    }

    @AfterAll
    fun stop() {
        redis.close()
    }

    private fun uri(wachtwoord: String?) =
        RedisURI
            .builder()
            .withHost(redis.host)
            .withPort(redis.poort)
            .apply { wachtwoord?.let { withAuthentication("nlportal", it.toCharArray()) } }
            .build()

    private fun <T> instantie(actie: (AssertableReactiveWebApplicationContext) -> T): T {
        var resultaat: T? = null
        redis.runner().run { context ->
            assertThat(context).hasNotFailed()
            resultaat = actie(context)
        }
        @Suppress("UNCHECKED_CAST")
        return resultaat as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun AssertableReactiveWebApplicationContext.sessies() = getBean(ReactiveSessionRepository::class.java) as ReactiveSessionRepository<Session>

    private fun AssertableReactiveWebApplicationContext.template() = getBean(ReactiveStringRedisTemplate::class.java)

    private fun AssertableReactiveWebApplicationContext.opslag() = getBean(EenmaligeOpslag::class.java)

    private fun AssertableReactiveWebApplicationContext.bewaar(
        attributen: Map<String, Any> = mapOf("nlp.tenant" to "00000099000000000001"),
        inactiviteit: Duration = Duration.ofMinutes(30),
    ): String =
        sessies()
            .createSession()
            .flatMap { sessie ->
                sessie.maxInactiveInterval = inactiviteit
                attributen.forEach { (naam, waarde) -> sessie.setAttribute(naam, waarde) }
                sessies().save(sessie).thenReturn(sessie.id)
            }.block()!!

    @Test
    fun `een sessie-ID heeft 43 tekens base64url, 256 bit`() {
        instantie { context ->
            val ids = (1..20).map { context.bewaar() }.toSet()

            assertThat(ids).hasSize(20)
            assertThat(ids).allMatch { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
        }
    }

    @Test
    fun `een bewaarde sessie is terug te lezen met haar attributen`() {
        instantie { context ->
            val id = context.bewaar(mapOf("nlp.tenant" to "00000099000000000001", "nlp.aangemaakt" to 1_700_000_000_000L))

            val sessie = context.sessies().findById(id).block()!!

            assertThat(sessie.getAttribute<String>("nlp.tenant")).isEqualTo("00000099000000000001")
            assertThat(sessie.getAttribute<Long>("nlp.aangemaakt")).isEqualTo(1_700_000_000_000L)
        }
    }

    @Test
    fun `elke sleutel in Redis heeft een TTL`() {
        instantie { context ->
            context.bewaar()
            context.opslag().zet("nlp:flow:ttl-test", "x", Duration.ofMinutes(5)).block()

            val sleutels =
                context
                    .template()
                    .scan(
                        ScanOptions
                            .scanOptions()
                            .match("*")
                            .count(1000)
                            .build(),
                    ).collectList()
                    .block()!!
            assertThat(sleutels).anyMatch { it.startsWith("spring:session:") }.anyMatch { it == "nlp:flow:ttl-test" }
            sleutels.forEach { sleutel ->
                val ttl = context.template().getExpire(sleutel).block()!!
                assertThat(ttl).describedAs("TTL van %s", sleutel).isPositive()
            }
        }
    }

    @Test
    fun `een pre-auth-waarde is eenmalig, ook over twee app-instanties`() {
        val a = redis.runner()
        val b = redis.runner()
        a.run { ctxA ->
            b.run { ctxB ->
                repeat(25) { n ->
                    val sleutel = "nlp:flow:replay-$n"
                    ctxA.opslag().zet(sleutel, "geheim", Duration.ofMinutes(1)).block()

                    val uitkomsten =
                        Flux
                            .merge(ctxA.opslag().neem(sleutel).map { "A" }, ctxB.opslag().neem(sleutel).map { "B" })
                            .collectList()
                            .block()!!

                    assertThat(uitkomsten).describedAs("neem bij %s", sleutel).hasSize(1)
                    assertThat(ctxA.opslag().neem(sleutel).block()).isNull()
                    assertThat(ctxB.opslag().lees(sleutel).block()).isNull()
                }
            }
        }
    }

    @Test
    fun `lezen en verwijderen werken tegen Redis en de waarde verloopt`() {
        instantie { context ->
            val opslag = context.opslag()
            opslag.zet("nlp:flow:les", "waarde", Duration.ofMinutes(1)).block()
            assertThat(opslag.lees("nlp:flow:les").block()).isEqualTo("waarde")
            opslag.verwijder("nlp:flow:les").block()
            assertThat(opslag.lees("nlp:flow:les").block()).isNull()

            opslag.zet("nlp:flow:kort", "waarde", Duration.ofMillis(200)).block()
            Mono.delay(Duration.ofMillis(400)).block()
            assertThat(opslag.neem("nlp:flow:kort").block()).isNull()
        }
    }

    @Test
    fun `de sessieserializer weigert een vreemd type bij het opslaan`() {
        instantie { context ->
            val sessie = context.sessies().createSession().block()!!
            sessie.setAttribute("vreemd", Date())

            assertThatThrownBy { context.sessies().save(sessie).block() }.hasStackTraceContaining("Niet-toegestane sessiewaarde").hasStackTraceContaining("java.util.Date")
        }
    }

    @Test
    fun `de sessieserializer weigert een vreemd type bij het lezen`() {
        instantie { context ->
            val id = context.bewaar()
            // een aanvaller (of een oude versie) schrijft een Java-geserialiseerd object in het attribuut
            val kwaadaardig = RedisSerializer.java().serialize(Date())!!
            val client = RedisClient.create()
            client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE), uri(redis.wachtwoord)).use {
                it.sync().hset("spring:session:sessions:$id", "sessionAttr:vreemd", kwaadaardig)
            }
            client.shutdown()

            assertThatThrownBy { context.sessies().findById(id).block() }.hasStackTraceContaining("Niet-toegestane sessiewaarde")
        }
    }

    @Test
    fun `de ACL-gebruiker werkt op de eigen sleutels en niet daarbuiten`() {
        instantie { context ->
            val template = context.template()
            template.opsForValue().set("nlp:acl", "ja", Duration.ofMinutes(1)).block()
            assertThat(template.opsForValue().get("nlp:acl").block()).isEqualTo("ja")

            assertThatThrownBy { template.opsForValue().set("ander:sleutel", "nee", Duration.ofMinutes(1)).block() }.hasStackTraceContaining("NOPERM")
            assertThatThrownBy { template.execute { it.serverCommands().flushAll() }.blockFirst() }.hasStackTraceContaining("NOPERM")
        }
    }

    @Test
    fun `de default-gebruiker en een fout wachtwoord worden geweigerd`() {
        val client = RedisClient.create()
        assertThatThrownBy { client.connect(uri(null)).sync().ping() }.hasStackTraceContaining("NOAUTH")
        assertThatThrownBy { client.connect(uri("fout")).sync().ping() }.hasStackTraceContaining("WRONGPASS")
        client.shutdown()
    }

    @Test
    fun `na een herstart van Redis is er niets bewaard, want er is geen persistentie`() {
        assertThat(cli("SET", "nlp:persistentie", "x")).contains("OK")
        assertThat(cli("GET", "nlp:persistentie")).contains("x")

        redis.container.dockerClient
            .restartContainerCmd(redis.container.containerId)
            .exec()
        val tot = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (!cli("PING").contains("PONG") && System.nanoTime() < tot) Thread.sleep(250)

        assertThat(cli("PING")).contains("PONG")
        assertThat(cli("EXISTS", "nlp:persistentie").trim()).isEqualTo("0")
    }

    private fun cli(vararg opdracht: String): String = redis.container.execInContainer("redis-cli", "--user", "nlportal", "--pass", redis.wachtwoord, "--no-auth-warning", *opdracht).stdout
}