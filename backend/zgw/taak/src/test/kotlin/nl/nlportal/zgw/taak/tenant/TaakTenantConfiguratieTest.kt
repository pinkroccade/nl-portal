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
package nl.nlportal.zgw.taak.tenant

import nl.nlportal.core.tenancy.ModuleContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.ServiceLoader
import nl.nlportal.core.tenancy.TenantModuleConfiguratie

class TaakTenantConfiguratieTest {
    private val spi = TaakTenantConfiguratie()

    @Test
    fun `de SPI is geregistreerd onder de naam taak`() {
        val namen = ServiceLoader.load(TenantModuleConfiguratie::class.java).map { it.naam }
        assertThat(namen).contains("taak")
    }

    @Test
    fun `een absolute https-URL is geldig`() {
        assertThat(spi.valideer(TaakConfiguratie("https://objecttypen.example/api/v1/objecttypes/1"))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "niet-een-url", "ftp://host/pad", "/relatief/pad", "http://"])
    fun `een ongeldige objecttype-URL wordt geweigerd zonder de waarde te noemen`(url: String) {
        val overtredingen = spi.valideer(TaakConfiguratie(url))

        assertThat(overtredingen).hasSize(1)
        assertThat(overtredingen.single().pad).isEqualTo("objectTypeUrl")
        assertThat(overtredingen.single().bericht).doesNotContain(url.ifEmpty { "\u0000" })
    }

    @Test
    fun `modules taak zonder de koppeling objectenapi wordt geweigerd`() {
        val config = TaakConfiguratie("https://objecttypen.example/api/v1/objecttypes/1")

        assertThat(spi.valideer(config, ModuleContext(koppelingen = setOf("objectenapi")))).isEmpty()
        val overtredingen = spi.valideer(config, ModuleContext(koppelingen = setOf("zakenapi")))
        assertThat(overtredingen).hasSize(1)
        assertThat(overtredingen.single().bericht).contains("objectenapi").doesNotContain("objecttypen.example")
    }
}