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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isA
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.serializer.SerializationException
import org.springframework.session.data.redis.ReactiveRedisSessionRepository
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.Base64
import java.util.Date

class RedisEenmaligeOpslagTest {
    private val waarden = mock<ReactiveValueOperations<String, String>>()
    private val redis =
        mock<ReactiveStringRedisTemplate> {
            on { opsForValue() } doReturn waarden
        }
    private val opslag = RedisEenmaligeOpslag(redis)

    @Test
    fun `zet bewaart de waarde met een TTL`() {
        whenever(waarden.set("nlp:flow:a", "w", Duration.ofMinutes(5))).thenReturn(Mono.just(true))

        StepVerifier.create(opslag.zet("nlp:flow:a", "w", Duration.ofMinutes(5))).verifyComplete()

        verify(waarden).set("nlp:flow:a", "w", Duration.ofMinutes(5))
    }

    @Test
    fun `lees geeft de waarde of niets`() {
        whenever(waarden.get("nlp:flow:a")).thenReturn(Mono.just("w"))
        whenever(waarden.get("nlp:flow:b")).thenReturn(Mono.empty())

        StepVerifier.create(opslag.lees("nlp:flow:a")).expectNext("w").verifyComplete()
        StepVerifier.create(opslag.lees("nlp:flow:b")).verifyComplete()
    }

    @Test
    fun `neem leest en verwijdert met een enkele GETDEL, nooit eerst lezen`() {
        whenever(waarden.getAndDelete("nlp:flow:a")).thenReturn(Mono.just("w"), Mono.empty())

        StepVerifier.create(opslag.neem("nlp:flow:a")).expectNext("w").verifyComplete()
        StepVerifier.create(opslag.neem("nlp:flow:a")).verifyComplete()

        verify(waarden, times(2)).getAndDelete("nlp:flow:a")
        verify(waarden, never()).get(any<String>())
    }

    @Test
    fun `verwijder wist de sleutel`() {
        whenever(redis.delete(eq("nlp:flow:a"))).thenReturn(Mono.just(1L))

        StepVerifier.create(opslag.verwijder("nlp:flow:a")).verifyComplete()

        verify(redis).delete("nlp:flow:a")
    }

    @Test
    fun `een fout van Redis bereikt de aanroeper en wordt niet ingeslikt`() {
        whenever(waarden.getAndDelete("nlp:flow:a")).thenReturn(Mono.error(IllegalStateException("redis weg")))
        whenever(waarden.set(any<String>(), any<String>(), any<Duration>())).thenReturn(Mono.error(IllegalStateException("redis weg")))

        StepVerifier.create(opslag.neem("nlp:flow:a")).expectErrorMessage("redis weg").verify()
        StepVerifier.create(opslag.zet("nlp:flow:a", "w", Duration.ofMinutes(1))).expectErrorMessage("redis weg").verify()
    }
}

class SessieSerializerTest {
    private val serializer = SessieSerializer()

    @Test
    fun `tekst, getallen en booleans gaan heen en terug`() {
        listOf<Any>("tekst met spatie en é", "", 42L, Long.MIN_VALUE, 7, true, false).forEach {
            assertThat(serializer.deserialize(serializer.serialize(it))).describedAs("%s", it).isEqualTo(it)
        }
    }

    @Test
    fun `de opgeslagen vorm is een voorvoegsel plus tekst`() {
        assertThat(String(serializer.serialize("x"))).isEqualTo("sx")
        assertThat(String(serializer.serialize(5L))).isEqualTo("l5")
        assertThat(String(serializer.serialize(5))).isEqualTo("i5")
        assertThat(String(serializer.serialize(true))).isEqualTo("btrue")
    }

    @Test
    fun `null is een lege waarde en een lege waarde is null`() {
        assertThat(serializer.serialize(null)).isEmpty()
        assertThat(serializer.deserialize(null)).isNull()
        assertThat(serializer.deserialize(ByteArray(0))).isNull()
    }

    @Test
    fun `een ander type wordt bij het opslaan geweigerd met de naam van het type`() {
        assertThatThrownBy { serializer.serialize(Date()) }
            .isInstanceOf(SerializationException::class.java)
            .hasMessageContaining("java.util.Date")
        assertThatThrownBy { serializer.serialize(listOf("a")) }.isInstanceOf(SerializationException::class.java)
    }

    @Test
    fun `een onbekend voorvoegsel, zoals een Java-geserialiseerd object, wordt bij het lezen geweigerd`() {
        val javaSerialisatie = byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00, 0x05)
        assertThatThrownBy { serializer.deserialize(javaSerialisatie) }.isInstanceOf(SerializationException::class.java)
        assertThatThrownBy { serializer.deserialize("zwaarwaarde".toByteArray()) }.isInstanceOf(SerializationException::class.java)
    }

    @Test
    fun `een onleesbaar getal of een boolean buiten true en false wordt geweigerd`() {
        listOf("lgeen", "iabc", "l", "bTRUE", "bja", "b").forEach {
            assertThatThrownBy { serializer.deserialize(it.toByteArray()) }
                .describedAs(it)
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("Niet-toegestane sessiewaarde")
        }
    }
}

class BffRedisConfigurationTest {
    private val configuratie = BffRedisConfiguration()

    @Test
    fun `de sessie-ID-generator geeft 43 tekens base64url van 256 bit`() {
        val ids = (1..50).map { SecureSessionIdGenerator().generate() }

        assertThat(ids.toSet()).hasSize(50)
        assertThat(ids).allMatch { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
        assertThat(Base64.getUrlDecoder().decode(ids.first())).hasSize(32)
    }

    @Test
    fun `de customizer zet de eigen generator op de Redis-repository`() {
        val repository = mock<ReactiveRedisSessionRepository>()

        configuratie.sessieIdGeneratorCustomizer().customize(repository)

        verify(repository).setSessionIdGenerator(isA<SecureSessionIdGenerator>())
    }

    @Test
    fun `de serializer van de sessie is de allowlist`() {
        assertThat(configuratie.sessieSerializer()).isInstanceOf(SessieSerializer::class.java)
    }

    @Test
    fun `Lettuce stuurt geen MAINT_NOTIFICATIONS`() {
        val builder = LettuceClientConfiguration.builder()

        configuratie.geenOnderhoudsmeldingen().customize(builder)

        val opties = builder.build().clientOptions.get()
        assertThat(opties.maintNotificationsConfig.maintNotificationsEnabled()).isFalse()
    }

    @Test
    fun `de Redis-opslag wordt uit de template gebouwd`() {
        assertThat(configuratie.eenmaligeOpslag(mock<ReactiveStringRedisTemplate>())).isInstanceOf(RedisEenmaligeOpslag::class.java)
    }
}