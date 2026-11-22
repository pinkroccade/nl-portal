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
package nl.nlportal.core.tenancy

import io.micrometer.common.KeyValue
import io.micrometer.common.KeyValues
import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import org.slf4j.MDC
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import reactor.core.publisher.Hooks
import reactor.util.context.Context

/** Voegt de tenant (OIN) als label toe aan de HTTP-metrics en -traces van elk request. */
class TenantServerRequestObservationConvention : DefaultServerRequestObservationConvention() {
    override fun getLowCardinalityKeyValues(context: ServerRequestObservationContext): KeyValues {
        val oin = context.attributes[TenantWebFilter.ATTRIBUUT]?.toString() ?: GEEN_TENANT
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of(OIN_LABEL, oin))
    }

    companion object {
        const val OIN_LABEL = "oin"
        const val GEEN_TENANT = "none"
    }
}

/**
 * De brug van de Reactor Context naar de MDC via Micrometer context-propagation: elke logregel die tijdens een request
 * wordt geschreven krijgt `oin`. Dit is de enige plek in `core` die MDC (thread-local) aanraakt.
 *
 * De brug leest de MDC terug om de waarde over een schedulerwissel mee te nemen, en gebruikt daarvoor een eigen
 * contextsleutel ([CONTEXT_SLEUTEL], als tekst) en niet die van [TenantContext]. Een MDC-waarde kan zo nooit als
 * tenant in de Reactor Context terechtkomen: [TenantContext] leest alleen wat [TenantWebFilter] erin schreef.
 */
object OinMdcBridge {
    const val MDC_SLEUTEL = "oin"

    /** Sleutel in de Reactor Context voor de MDC-brug; bewust niet de sleutel van [TenantContext]. */
    internal const val CONTEXT_SLEUTEL = "nl.nlportal.core.tenancy.OinMdc"

    init {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            object : ThreadLocalAccessor<String> {
                override fun key(): Any = CONTEXT_SLEUTEL

                override fun getValue(): String? = MDC.get(MDC_SLEUTEL)

                override fun setValue(value: String) = MDC.put(MDC_SLEUTEL, value)

                override fun setValue() = MDC.remove(MDC_SLEUTEL)
            },
        )
        Hooks.enableAutomaticContextPropagation()
    }

    /** Zet de OIN in [context] voor de MDC-brug; [TenantWebFilter] schrijft dit samen met de tenant zelf. */
    internal fun schrijf(
        context: Context,
        oin: Oin,
    ): Context = context.put(CONTEXT_SLEUTEL, oin.value)
}