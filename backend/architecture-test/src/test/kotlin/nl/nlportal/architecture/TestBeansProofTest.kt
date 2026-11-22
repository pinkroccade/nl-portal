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
import java.nio.file.Files

/** Bewijst dat de regel `één bron per testbean` een eigen definitie in een moduletest vangt. */
class TestBeansProofTest {
    @Test
    fun `a bean method with a protected name is caught`() {
        val tekst = "@Bean\n    open fun geheimenBron() = TestTenants.ALLE_GEHEIMEN\n"

        assertThat(TestBeans.overtredingen(tekst)).containsExactly("geheimenBron")
    }

    @Test
    fun `a bean method returning a protected type under another name is caught`() {
        val tekst = "@Bean\n@Primary\nfun anders(): TenantDirectory = x()\n@Bean\nfun ook() = VasteTenantWebFilter()\n"

        assertThat(TestBeans.overtredingen(tekst)).containsExactly("anders", "ook")
    }

    @Test
    fun `an unrelated bean and a plain function are not caught`() {
        val tekst = "@Bean\nfun mockWebServer() = MockWebServer()\nfun tenantDirectory() = TestTenants.directory()\n"

        assertThat(TestBeans.overtredingen(tekst)).isEmpty()
    }

    @Test
    fun `a module test with its own definition is found, an app test is exempt`() {
        val root = Files.createTempDirectory("testbeans")
        val bron = "package x\n@Bean\nfun tenantDirectory() = TenantDirectory(listOf())\n"
        for (module in listOf("kvk", "app")) {
            val map = root.resolve("$module/src/test/kotlin").also { Files.createDirectories(it) }
            Files.writeString(map.resolve("EigenBeans.kt"), bron)
        }

        val gevonden = TestBeans.overtreders(listOf(root.resolve("kvk"), root.resolve("app")))

        assertThat(gevonden).containsExactly("kvk/kotlin/EigenBeans.kt")
    }
}