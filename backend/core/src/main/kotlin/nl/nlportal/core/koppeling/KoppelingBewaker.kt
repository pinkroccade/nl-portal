/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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

/** De standaardwaarden onder `nl-portal.koppeling-resilience` voor de limiet, de circuit breaker en de timeout van elke tenant en koppeling. */
@ConfigurationProperties(prefix = "nl-portal.koppeling-resilience")
data class KoppelingResilienceInstellingen(
    val maxGelijktijdig: Int = 25,
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

    /** Als [beschermd], voor een stroom: de plek in de limiet en de circuit breaker gelden tot de stroom klaar, geannuleerd of mislukt is. */
    fun <T : Any> beschermdStroom(aanroep: Flux<T>): Flux<T> =
        aanroep
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .onErrorMap(BulkheadFullException::class.java) { KoppelingException.gesynthetiseerd(koppeling, KoppelingFoutsoort.LIMIET, cause = it) }
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorMap(CallNotPermittedException::class.java) { KoppelingException.gesynthetiseerd(koppeling, KoppelingFoutsoort.CIRCUIT_OPEN, cause = it) }
}
