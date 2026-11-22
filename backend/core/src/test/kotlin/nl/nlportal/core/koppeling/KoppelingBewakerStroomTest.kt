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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration

/**
 * De stroombewaker zonder netwerk en zonder klok: een stroom die na de eerste respons stokt en eindigt (door een stiltetimeout of
 * doordat de afnemer annuleert) opent de circuit breaker niet en geeft zijn plek in de limiet vrij.
 */
class KoppelingBewakerStroomTest {
    private fun bewaker() =
        KoppelingBewaker(
            "documenten",
            "tenant/documenten",
            KoppelingResilienceInstellingen(maxGelijktijdigeStromen = 1, vensterGrootte = 2, minimumAanroepen = 2),
        )

    private fun timeout() = KoppelingException.gesynthetiseerd("documenten", KoppelingFoutsoort.TIMEOUT)

    private fun controleerGezond(bewaker: KoppelingBewaker) {
        val metadata = bewaker.beschermd(Mono.just("{}")).block()
        assertThat(metadata).isEqualTo("{}")
        val stroom = bewaker.beschermdStroom { fase -> Flux.just("a").doOnSubscribe { fase.koptekstOntvangen() } }.collectList().block()
        assertThat(stroom).containsExactly("a")
    }

    @Test
    fun `een stiltetimeout na de eerste respons telt niet als storing en geeft de plek vrij`() {
        val bewaker = bewaker()

        repeat(4) {
            val bron = Sinks.many().unicast().onBackpressureBuffer<String>()
            val ontvangen = mutableListOf<Throwable>()
            bewaker
                .beschermdStroom { fase ->
                    fase.koptekstOntvangen()
                    bron.asFlux()
                }.subscribe({}, { ontvangen += it })
            bron.tryEmitNext("brok")
            bron.tryEmitError(timeout())

            assertThat(ontvangen).hasSize(1)
        }

        controleerGezond(bewaker)
    }

    @Test
    fun `annuleren na de eerste respons telt niet als storing en geeft de plek vrij`() {
        val bewaker = bewaker()

        repeat(4) {
            val bron = Sinks.many().unicast().onBackpressureBuffer<String>()
            val abonnement =
                bewaker
                    .beschermdStroom { fase ->
                        fase.koptekstOntvangen()
                        bron.asFlux()
                    }.subscribe()
            bron.tryEmitNext("brok")
            abonnement.dispose()
        }

        controleerGezond(bewaker)
    }

    @Test
    fun `een storing voor de eerste respons telt wel en opent de circuit breaker`() {
        val bewaker = bewaker()

        repeat(2) { runCatching { bewaker.beschermdStroom<String> { Flux.error(timeout()) }.blockLast() } }
        val derde = runCatching { bewaker.beschermdStroom<String> { Flux.just("a") }.blockLast() }.exceptionOrNull()

        assertThat((derde as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.CIRCUIT_OPEN)
    }

    private fun stroomResultaat(
        bewaker: KoppelingBewaker,
        bouw: (StroomFase) -> Flux<String>,
    ) = runCatching { bewaker.beschermdStroom(bouw).blockLast() }.exceptionOrNull()

    @Test
    fun `een geweigerde stroom bij een open circuit breaker houdt de plek in de limiet niet vast`() {
        val bewaker = bewaker()
        repeat(2) { stroomResultaat(bewaker) { Flux.error(timeout()) } }

        // Met één plek in de limiet blijft elke volgende weigering CIRCUIT_OPEN zolang de plek terugkomt (anders LIMIET).
        repeat(3) {
            val fout = stroomResultaat(bewaker) { Flux.just("a") }
            assertThat((fout as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.CIRCUIT_OPEN)
        }
    }

    @Test
    fun `een tweede gelijktijdige stroom boven de limiet wordt geweigerd met LIMIET`() {
        val bewaker = bewaker()
        val bron = Sinks.many().unicast().onBackpressureBuffer<String>()
        val eerste = bewaker.beschermdStroom { bron.asFlux() }.subscribe({}, {})

        val fout = stroomResultaat(bewaker) { Flux.just("a") }
        eerste.dispose()

        assertThat((fout as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.LIMIET)
    }

    @Test
    fun `een geslaagde eerste respons telt als succes in het venster van de circuit breaker`() {
        val bewaker = bewaker()
        bewaker.beschermdStroom { fase -> Flux.just("a").doOnSubscribe { fase.koptekstOntvangen() } }.blockLast()
        stroomResultaat(bewaker) { Flux.error(timeout()) }

        // 1 succes + 1 storing in een venster van 2 aanroepen = 50%: de circuit breaker is open.
        val volgende = stroomResultaat(bewaker) { Flux.just("a") }

        assertThat((volgende as KoppelingException).soort).isEqualTo(KoppelingFoutsoort.CIRCUIT_OPEN)
    }

    @Test
    fun `een onverwachte fout die geen KoppelingException is telt niet als storing`() {
        val bewaker = bewaker()

        repeat(3) { stroomResultaat(bewaker) { Flux.error(IllegalStateException("kapot")) } }
        val volgende = runCatching { bewaker.beschermdStroom { Flux.just("a") }.blockLast() }

        assertThat(volgende.getOrNull()).isEqualTo("a")
    }

    @Test
    fun `een onverwachte fout bij een gewone aanroep telt niet als storing`() {
        val bewaker = bewaker()

        repeat(3) { runCatching { bewaker.beschermd(Mono.error<String>(IllegalStateException("kapot"))).block() } }

        assertThat(bewaker.beschermd(Mono.just("ok")).block()).isEqualTo("ok")
    }

    @Test
    fun `annuleren voor de eerste respons in halfopen geeft de proefplek terug`() {
        val bewaker =
            KoppelingBewaker(
                "documenten",
                "tenant/documenten-halfopen",
                KoppelingResilienceInstellingen(
                    vensterGrootte = 2,
                    minimumAanroepen = 2,
                    wachttijdOpen = Duration.ofMillis(10),
                    halfOpenAanroepen = 1,
                ),
            )
        repeat(2) { stroomResultaat(bewaker) { Flux.error(timeout()) } }
        Thread.sleep(100)

        val bron = Sinks.many().unicast().onBackpressureBuffer<String>()
        bewaker.beschermdStroom { bron.asFlux() }.subscribe().dispose()

        // De proefplek is terug: een volgende aanroep wordt toegelaten in plaats van CIRCUIT_OPEN.
        assertThat(stroomResultaat(bewaker) { Flux.just("a") }).isNull()
    }
}