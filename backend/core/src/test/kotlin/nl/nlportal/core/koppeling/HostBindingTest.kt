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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI

class HostBindingTest {
    @Test
    fun `de standaardpoort van het schema geldt als de poort ontbreekt`() {
        assertThat(HostBinding.oplossen(URI("https://bron.example/api"), "https://bron.example:443/api/x")).isEqualTo(URI("https://bron.example:443/api/x"))
        assertThat(HostBinding.oplossen(URI("http://bron.example/api"), "http://bron.example:80/api/x").path).isEqualTo("/api/x")
        assertThatThrownBy { HostBinding.oplossen(URI("https://bron.example/api"), "https://bron.example:80/api/x") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `een onleesbare URL wordt geweigerd zonder de URL te noemen`() {
        assertThatThrownBy { HostBinding.oplossen(URI("https://bron.example/api"), "http://[ongeldig") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageNotContaining("ongeldig[")
    }

    @Test
    fun `een relatief pad komt onder de basis en query-waarden worden gecodeerd`() {
        val uri = HostBinding.oplossen(URI("https://bron.example/api"), "zaken/1", mapOf("a b" to listOf("1+2", "&")))

        assertThat(uri.rawPath).isEqualTo("/api/zaken/1")
        assertThat(uri.rawQuery).isEqualTo("a%20b=1%2B2&a%20b=%26")
    }
}