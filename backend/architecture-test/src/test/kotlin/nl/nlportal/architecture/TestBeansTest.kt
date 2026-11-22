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
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** De regel `één bron per testbean` op de echte moduletests. Het bewijs dat ze vangt staat in [TestBeansProofTest]. */
class TestBeansTest {
    @Test
    fun `module tests define no test bean of their own beyond the known offenders`() {
        val modules =
            System
                .getProperty("architecture.modules")
                .split("|")
                .filter { it.isNotBlank() }
                .map { Path.of(it.substringAfter("=")) }

        val gevonden = TestBeans.overtreders(modules)

        assertThat(gevonden - TestBeans.BEKENDE_OVERTREDERS)
            .describedAs("Nieuwe eigen definitie van tenantDirectory/geheimenBron/vasteTenantWebFilter in een moduletest; gebruik TestTenants")
            .isEmpty()
        assertThat(TestBeans.BEKENDE_OVERTREDERS - gevonden)
            .describedAs("Opgeruimd: haal deze bestanden uit TestBeans.BEKENDE_OVERTREDERS")
            .isEmpty()
    }
}