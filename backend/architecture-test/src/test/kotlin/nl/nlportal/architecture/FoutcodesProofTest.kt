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
package nl.nlportal.architecture

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Bewijst dat het foutcodecontract een verwijderde, hernoemde of nieuwe code vangt. */
class FoutcodesProofTest {
    private val handler =
        """
        is ModuleNotAvailableException -> Uitkomst(ex.message, mapOf("code" to "MODULE_NOT_AVAILABLE", "module" to ex.module))
        is TenantContextMissingException -> intern(ex, "Niet geautoriseerd", "TENANT_ONTBREEKT")
        """.trimIndent()
    private val rest =
        """
        ResponseEntity(mapOf("code" to "KOPPELING_FOUT", "correlatieId" to e.correlatieId), HttpStatus.BAD_GATEWAY)
        intern(e, "MODULE_NIET_GECONFIGUREERD")
        """.trimIndent()

    @Test
    fun `the scan finds codes in a map entry and in an intern call, per surface`() {
        val codes = Foutcodes.scan(mapOf("graphql" to handler, "rest" to rest))

        assertThat(codes).containsExactly(
            "graphql: MODULE_NOT_AVAILABLE",
            "graphql: TENANT_ONTBREEKT",
            "rest: KOPPELING_FOUT",
            "rest: MODULE_NIET_GECONFIGUREERD",
        )
    }

    @Test
    fun `a renamed code fails with the old and the new name`() {
        val snapshot = Foutcodes.scan(mapOf("graphql" to handler))
        val hernoemd = Foutcodes.scan(mapOf("graphql" to handler.replace("MODULE_NOT_AVAILABLE", "MODULE_ONBESCHIKBAAR")))

        assertThatThrownBy { Foutcodes.assertGelijk(snapshot, hernoemd) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("graphql: MODULE_NOT_AVAILABLE")
            .hasMessageContaining("graphql: MODULE_ONBESCHIKBAAR")
    }

    @Test
    fun `a new code fails until the snapshot is updated on purpose`() {
        val snapshot = Foutcodes.scan(mapOf("graphql" to handler))
        val nieuw = Foutcodes.scan(mapOf("graphql" to "$handler\nintern(ex, \"x\", \"NIEUWE_CODE\")"))

        assertThatThrownBy { Foutcodes.assertGelijk(snapshot, nieuw) }.hasMessageContaining("graphql: NIEUWE_CODE")
    }

    @Test
    fun `an unchanged contract passes`() {
        Foutcodes.assertGelijk(Foutcodes.scan(mapOf("graphql" to handler)), Foutcodes.scan(mapOf("graphql" to handler)))
    }
}