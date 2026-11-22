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
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Seam: de lijst vertrouwde proxy-adressen zoals de beheerder die configureert. */
class TrustedProxiesTest {
    @Test
    fun `een blok dat elk adres vertrouwt weigert het opstarten`() {
        for (blok in listOf("0.0.0.0/0", "::/0", "0:0:0:0:0:0:0:0/0")) {
            assertThatThrownBy { TrustedProxies(listOf("10.0.0.0/8", blok)) }
                .describedAs(blok)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(blok)
        }
    }

    @Test
    fun `een gewoon blok en een los adres vertrouwen alleen hun eigen adressen`() {
        val proxies = TrustedProxies(listOf("10.0.0.0/8", "192.0.2.7"))

        assertThat(proxies.trusts(InetSocketAddress(InetAddress.getByName("10.9.8.7"), 1))).isTrue()
        assertThat(proxies.trusts(InetSocketAddress(InetAddress.getByName("192.0.2.7"), 1))).isTrue()
        assertThat(proxies.trusts(InetSocketAddress(InetAddress.getByName("192.0.2.8"), 1))).isFalse()
    }
}