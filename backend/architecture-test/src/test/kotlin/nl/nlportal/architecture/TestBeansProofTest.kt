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
import org.assertj.core.api.Assertions.entry
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

        assertThat(gevonden).containsExactly(entry("kvk/kotlin/EigenBeans.kt", 1))
    }

    @Test
    fun `a one-line bean method is caught`() {
        assertThat(TestBeans.overtredingen("@Bean fun tenantDirectory() = TestTenants.directory()\n")).containsExactly("tenantDirectory")
    }

    @Test
    fun `bean methods with modifiers are caught`() {
        val tekst =
            "@Bean\ninternal fun geheimenBron() = x()\n@Bean\nprivate fun tenantDirectory() = x()\n" +
                "@Bean\noverride fun vasteTenantWebFilter() = x()\n@Bean\nopen suspend fun a(): TenantDirectory = x()\n"

        assertThat(TestBeans.overtredingen(tekst))
            .containsExactly("geheimenBron", "tenantDirectory", "vasteTenantWebFilter", "a")
    }

    @Test
    fun `a bean annotation with arguments over several lines is caught`() {
        val tekst = "@Bean(\n    name = [\"x\"],\n    autowireCandidate = false,\n)\n@Primary\nfun anders(): TenantDirectory = x()\n"

        assertThat(TestBeans.overtredingen(tekst)).containsExactly("anders")
    }

    @Test
    fun `a return type with a package path is caught`() {
        val tekst = "@Bean\nfun a(): nl.nlportal.core.tenant.TenantDirectory = x()\n@Bean\nfun b() = nl.nlportal.GeheimenBron(x)\n"

        assertThat(TestBeans.overtredingen(tekst)).containsExactly("a", "b")
    }

    @Test
    fun `parameters with nested parentheses do not hide the return type`() {
        val tekst = "@Bean\nfun a(b: Foo = Foo(Bar(1)), c: String = \"(\"): TenantDirectory = x()\n"

        assertThat(TestBeans.overtredingen(tekst)).containsExactly("a")
    }

    @Test
    fun `a with-bean registration of a protected type is caught`() {
        val tekst =
            "runner.withBean(TenantDirectory::class.java) { x }\n" +
                "runner.withBean(\"naam\", nl.nlportal.GeheimenBron::class.java, { x })\n" +
                "runner.withBean<VasteTenantWebFilter>(  { x })\n" +
                "runner.withBean(Other::class.java) { x }\n"

        assertThat(TestBeans.overtredingen(tekst))
            .containsExactly("withBean(TenantDirectory)", "withBean(GeheimenBron)", "withBean(VasteTenantWebFilter)")
    }

    @Test
    fun `definitions are counted per file`() {
        val root = Files.createTempDirectory("testbeans-telling")
        val map = root.resolve("kvk/src/test/kotlin").also { Files.createDirectories(it) }
        Files.writeString(map.resolve("Twee.kt"), "@Bean fun tenantDirectory() = x()\n@Bean fun geheimenBron() = y()\n")

        assertThat(TestBeans.overtreders(listOf(root.resolve("kvk")))).containsExactly(entry("kvk/kotlin/Twee.kt", 2))
    }
}