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
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** Seam: het gepubliceerde JSON-schema is wat de Kotlin-typen beschrijven, en het gecommitte bestand is actueel. */
class TenantSchemaTest {
    private val schema: JsonNode = JsonMapper.builder().build().readTree(TenantSchemaGenerator.generate())

    @Test
    fun `het gecommitte schema is actueel`() {
        val bestand = Path.of("schema/tenant.schema.json")
        assertThat(Files.readString(bestand).replace("\r\n", "\n"))
            .describedAs("Schema verouderd: draai `mise run backend:tenant-schema` en commit het resultaat")
            .isEqualTo(TenantSchemaGenerator.generate())
    }

    @Test
    fun `het schema is gesloten en noemt de verplichte eigenschappen`() {
        assertThat(schema["additionalProperties"].asBoolean(true)).isFalse
        assertThat(schema["required"].strings()).containsExactly("oin", "naam", "omgevingstype", "domeinen", "thema", "inlogmiddelen")
        assertThat(schema["properties"]["thema"]["additionalProperties"].asBoolean(true)).isFalse
        assertThat(schema["properties"]["thema"]["required"].strings()).containsExactly("titel")
    }

    @Test
    fun `de OIN is een tekst van twintig cijfers, zodat een ongequote OIN niet voldoet`() {
        assertThat(schema["properties"]["oin"]["type"].asString()).isEqualTo("string")
        assertThat(schema["properties"]["oin"]["pattern"].asString()).isEqualTo("^[0-9]{20}$")
    }

    @Test
    fun `opsommingen volgen de enums en domeinen en inlogmiddelen mogen niet leeg zijn`() {
        assertThat(schema["properties"]["omgevingstype"]["enum"].strings()).containsExactly("productie", "acceptatie", "test", "ontwikkeling")
        assertThat(schema["properties"]["inlogmiddelen"]["items"]["enum"].strings()).contains("digid", "wallet", "test")
        assertThat(schema["properties"]["minimumNiveau"]["enum"].strings()).containsExactly("basis", "midden", "substantieel", "hoog")
        assertThat(schema["properties"]["domeinen"]["minItems"].asInt()).isEqualTo(1)
        assertThat(schema["properties"]["inlogmiddelen"]["minItems"].asInt()).isEqualTo(1)
        assertThat(schema["properties"]["sessie"]["properties"]["inactiviteit"]["type"].asString()).isEqualTo("string")
    }
}

private fun JsonNode.strings(): List<String> = (0 until size()).map { get(it).asString() }