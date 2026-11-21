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
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Seam: de validator zoals CI hem aanroept, via argumenten, uitvoer en exitcode. */
class TenantValidatorCliTest {
    @TempDir
    lateinit var dir: Path

    private class Uitvoer {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        fun run(vararg args: String) = TenantValidatorCli.run(args.toList(), PrintStream(out), PrintStream(err))

        val stdout get() = out.toString()
        val stderr get() = err.toString()
    }

    private fun schrijf(
        naam: String,
        inhoud: String,
    ) = Files.writeString(dir.resolve(naam), inhoud)

    @Test
    fun `een geldige map geeft exitcode 0`() {
        schrijf("base.yaml", "omgevingstype: test\ninlogmiddelen: [test]\n")
        schrijf("00000099000000000001.yaml", "oin: \"00000099000000000001\"\nnaam: N\ndomeinen: [a.example]\nthema:\n  titel: T\nauthenticatie:\n  issuer: https://login.example/realms/k1\n  sleutelBestand: /run/secrets/00000099000000000001/k0.jwk\n")
        val uitvoer = Uitvoer()

        assertThat(uitvoer.run(dir.toString())).isZero()
        assertThat(uitvoer.stdout).contains("OK: 1 tenant(s): 00000099000000000001")
    }

    @Test
    fun `een ongeldige map geeft exitcode 1 en noemt elke fout`() {
        schrijf("00000099000000000001.yaml", "oin: \"00000099000000000001\"\nnaam: N\ndomeinen: [A.example]\nthema:\n  titel: T\nauthenticatie:\n  issuer: https://login.example/realms/k2\n  sleutelBestand: /run/secrets/00000099000000000001/k1.jwk\ngeheim: abc\n")
        val uitvoer = Uitvoer()

        assertThat(uitvoer.run(dir.toString())).isEqualTo(1)
        assertThat(uitvoer.stderr).contains("ONBEKENDE_EIGENSCHAP").doesNotContain("abc")
    }

    @Test
    fun `verkeerd gebruik geeft exitcode 2`() {
        assertThat(Uitvoer().run()).isEqualTo(2)
        assertThat(Uitvoer().run("a", "b")).isEqualTo(2)
        assertThat(Uitvoer().run(dir.toString(), "--onbekend")).isEqualTo(2)
    }

    @Test
    fun `de optie meerdere-tenants staat een tweede tenant toe`() {
        schrijf("base.yaml", "omgevingstype: test\ninlogmiddelen: [test]\n")
        schrijf("00000099000000000001.yaml", "oin: \"00000099000000000001\"\nnaam: N\ndomeinen: [a.example]\nthema:\n  titel: T\nauthenticatie:\n  issuer: https://login.example/realms/k3\n  sleutelBestand: /run/secrets/00000099000000000001/k2.jwk\n")
        schrijf("00000099000000000002.yaml", "oin: \"00000099000000000002\"\nnaam: N\ndomeinen: [b.example]\nthema:\n  titel: T\nauthenticatie:\n  issuer: https://login.example/realms/k4\n  sleutelBestand: /run/secrets/00000099000000000002/k3.jwk\n")

        assertThat(Uitvoer().run(dir.toString())).isEqualTo(1)
        assertThat(Uitvoer().run(dir.toString(), "--meerdere-tenants")).isZero()
    }

    @Test
    fun `schema print het JSON-schema`() {
        val uitvoer = Uitvoer()

        assertThat(uitvoer.run("--schema")).isZero()
        assertThat(uitvoer.stdout).isEqualTo(TenantSchemaGenerator.generate())
    }
}