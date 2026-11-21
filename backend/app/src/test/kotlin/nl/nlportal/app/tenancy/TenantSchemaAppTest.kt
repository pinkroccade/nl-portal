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
package nl.nlportal.app.tenancy

import nl.nlportal.core.tenancy.TenantSchemaGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Seam: het gepubliceerde schema is wat de Kotlin-typen van core en van alle modules op het klassenpad van de app beschrijven. */
class TenantSchemaAppTest {
    @Test
    fun `het gecommitte schema is actueel`() {
        assertThat(Files.readString(Path.of("../schema/tenant.schema.json")).replace("\r\n", "\n"))
            .describedAs("Schema verouderd: draai `mise run backend:tenant-schema` en commit het resultaat")
            .isEqualTo(TenantSchemaGenerator.generate())
    }
}