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
 */
object OinMdcBridge {
    const val MDC_SLEUTEL = "oin"

    init {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            object : ThreadLocalAccessor<Oin> {
                override fun key(): Any = TenantContext.SLEUTEL

                override fun getValue(): Oin? = MDC.get(MDC_SLEUTEL)?.let(::Oin)

                override fun setValue(value: Oin) = MDC.put(MDC_SLEUTEL, value.value)

                override fun setValue() = MDC.remove(MDC_SLEUTEL)
            },
        )
        Hooks.enableAutomaticContextPropagation()
    }
}