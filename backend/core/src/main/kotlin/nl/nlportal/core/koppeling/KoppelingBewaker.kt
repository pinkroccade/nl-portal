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

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import org.springframework.boot.context.properties.ConfigurationProperties
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** De standaardwaarden onder `nl-portal.koppeling-resilience` voor de limiet, de circuit breaker en de timeout van elke tenant en koppeling. */
@ConfigurationProperties(prefix = "nl-portal.koppeling-resilience")
data class KoppelingResilienceInstellingen(
    val maxGelijktijdig: Int = 25,
    val maxGelijktijdigeStromen: Int = 10,
    val maxWachttijd: Duration = Duration.ZERO,
    val timeout: Duration = Duration.ofSeconds(30),
    val foutPercentage: Float = 50f,
    val vensterGrootte: Int = 20,
    val minimumAanroepen: Int = 10,
    val wachttijdOpen: Duration = Duration.ofSeconds(30),
    val halfOpenAanroepen: Int = 3,
)

/**
 * Limiet op gelijktijdige aanroepen en circuit breaker van één tenant en koppeling. Elke tenant en koppeling heeft
 * eigen instanties, zodat een trage bron van de ene gemeente de andere niet meesleept. Een 4xx telt niet als storing.
 */
internal class KoppelingBewaker(
    private val koppeling: String,
    instantieNaam: String,
    instellingen: KoppelingResilienceInstellingen,
) {
    val timeout: Duration = instellingen.timeout

    private val bulkhead =
        Bulkhead.of(
            instantieNaam,
            BulkheadConfig
                .custom()
                .maxConcurrentCalls(instellingen.maxGelijktijdig)
                .maxWaitDuration(instellingen.maxWachttijd)
                .build(),
        )

    private val stroomBulkhead =
        Bulkhead.of(
            "$instantieNaam:stroom",
            BulkheadConfig
                .custom()
                .maxConcurrentCalls(instellingen.maxGelijktijdigeStromen)
                .maxWaitDuration(Duration.ZERO)
                .build(),
        )

    private val circuitBreaker =
        CircuitBreaker.of(
            instantieNaam,
            CircuitBreakerConfig
                .custom()
                .failureRateThreshold(instellingen.foutPercentage)
                .slidingWindowSize(instellingen.vensterGrootte)
                .minimumNumberOfCalls(instellingen.minimumAanroepen)
                .waitDurationInOpenState(instellingen.wachttijdOpen)
                .permittedNumberOfCallsInHalfOpenState(instellingen.halfOpenAanroepen)
                .recordException { it is KoppelingException && it.isStoring }
                // Een 4xx en onze eigen weigeringen (limiet, host-binding) tellen niet mee, ook niet als succes.
                .ignoreException { it is KoppelingException && !it.isStoring }
                .build(),
        )

    fun <T : Any> beschermd(aanroep: Mono<T>): Mono<T> =
        aanroep
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .onErrorMap(BulkheadFullException::class.java) { KoppelingException.gesynthetiseerd(koppeling, KoppelingFoutsoort.LIMIET, cause = it) }
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorMap(CallNotPermittedException::class.java) { KoppelingException.gesynthetiseerd(koppeling, KoppelingFoutsoort.CIRCUIT_OPEN, cause = it) }

    /**
     * Als [beschermd], voor een stroom, met twee verschillen. Een stroom heeft een eigen limiet (`maxGelijktijdigeStromen`), zodat
     * lopende downloads de metadata-aanroepen niet verdringen. En de circuit breaker beoordeelt alleen de fase tot de eerste respons
     * (status en headers): wat daarna gebeurt, zoals een trage of afgebroken afnemer, zegt niets over de gezondheid van de bron.
     * Bouw de stroom met [StroomFase.koptekstOntvangen] aangeroepen zodra een succesvolle respons binnen is. De plek in de limiet
     * komt vrij bij klaar, fout en annuleren.
     */
    fun <T : Any> beschermdStroom(bouw: (StroomFase) -> Flux<T>): Flux<T> =
        Flux.defer {
            if (!stroomBulkhead.tryAcquirePermission()) {
                return@defer Flux.error(KoppelingException.gesynthetiseerd(koppeling, KoppelingFoutsoort.LIMIET))
            }
            if (!circuitBreaker.tryAcquirePermission()) {
                stroomBulkhead.onComplete()
                return@defer Flux.error(KoppelingException.gesynthetiseerd(koppeling, KoppelingFoutsoort.CIRCUIT_OPEN))
            }
            val start = System.nanoTime()
            val oordeel = AtomicBoolean(false)
            val fase =
                StroomFase {
                    if (oordeel.compareAndSet(false, true)) circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS)
                }
            bouw(fase)
                .doOnError { fout -> if (oordeel.compareAndSet(false, true)) circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, fout) }
                .doFinally {
                    stroomBulkhead.onComplete()
                    // Annuleren vóór de eerste respons: geen oordeel, de plek (ook in halfopen) gaat terug.
                    if (oordeel.compareAndSet(false, true)) circuitBreaker.releasePermission()
                }
        }
}

/** Het moment waarop een stroom zijn eerste respons kreeg; daarna oordeelt de circuit breaker niet meer. */
internal fun interface StroomFase {
    fun koptekstOntvangen()
}