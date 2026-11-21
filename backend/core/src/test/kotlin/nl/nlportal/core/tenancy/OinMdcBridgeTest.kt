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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/** Seam: de brug van de Reactor Context naar de MDC; de MDC is nooit een bron voor de tenant. */
class OinMdcBridgeTest {
    private val bosrand = Oin("00000099000000000001")

    init {
        OinMdcBridge
    }

    @AfterEach
    fun opruimen() = MDC.clear()

    @Test
    fun `een MDC-oin op de thread wordt nooit de tenant in de Reactor Context`() {
        MDC.put(OinMdcBridge.MDC_SLEUTEL, "00000099000000000002")

        assertThatThrownBy {
            Mono
                .defer { TenantContext.currentMono() }
                .contextCapture()
                .block()
        }.isInstanceOf(TenantContextMissingException::class.java)
    }

    @Test
    fun `een ongeldige MDC-oin raakt de capture niet`() {
        MDC.put(OinMdcBridge.MDC_SLEUTEL, "rommel")

        assertThatThrownBy { Mono.defer { TenantContext.currentMono() }.contextCapture().block() }
            .isInstanceOf(TenantContextMissingException::class.java)
    }

    @Test
    fun `de MDC bevat de OIN tijdens het request en is daarna leeg, ook op hergebruikte threads`() {
        repeat(20) {
            val tijdens =
                Mono
                    .fromCallable { MDC.get(OinMdcBridge.MDC_SLEUTEL) }
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite { OinMdcBridge.schrijf(it.put(TenantContext.SLEUTEL, bosrand), bosrand) }
                    .block()
            assertThat(tijdens).isEqualTo(bosrand.value)

            val erna =
                Mono
                    .fromCallable { MDC.get(OinMdcBridge.MDC_SLEUTEL) ?: "leeg" }
                    .subscribeOn(Schedulers.boundedElastic())
                    .block()
            assertThat(erna).describedAs("een volgend request zonder tenant op dezelfde threadpool").isEqualTo("leeg")
        }
        assertThat(MDC.get(OinMdcBridge.MDC_SLEUTEL)).isNull()
    }
}