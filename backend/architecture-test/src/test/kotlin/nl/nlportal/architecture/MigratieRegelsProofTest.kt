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

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Bewijst dat elke migratieregel het oude foute patroon vangt en een nette module laat staan. */
class MigratieRegelsProofTest {
    private val base = "nl.nlportal.architecture.fixtures"

    @Test
    fun `a module that reads koppelingen without a VervallenProperties bean fails`() {
        val klassen = ClassFileImporter().importPackages("$base.migratievio")

        assertThatThrownBy { MigratieRegels().contributorsRegisterVervallenProperties().check(klassen) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("ZonderVervallenClient")
    }

    @Test
    fun `a module with a VervallenProperties bean in its autoconfiguration passes`() {
        val klassen = ClassFileImporter().importPackages("$base.migratieclean.met")

        MigratieRegels().contributorsRegisterVervallenProperties().check(klassen)
    }

    @Test
    fun `a module in the not yet migrated list is exempt`() {
        val klassen = ClassFileImporter().importPackages("$base.migratieclean.uitgezonderd")

        assertThatThrownBy { MigratieRegels().contributorsRegisterVervallenProperties().check(klassen) }
            .isInstanceOf(AssertionError::class.java)
        val metEnZonder = ClassFileImporter().importPackages("$base.migratieclean")

        MigratieRegels(notYetMigrated = arrayOf("$base.migratieclean.uitgezonderd..")).contributorsRegisterVervallenProperties().check(metEnZonder)
    }
}