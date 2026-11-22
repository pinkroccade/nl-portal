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
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** Seam: een niet-gemigreerde module kan niet aan staan in een runtime met meer dan een tenant (epic #499, T22). */
class MeerdereTenantsGuardTest {
    @TempDir
    lateinit var dir: Path

    private val eerste = "00000099000000000001"
    private val tweede = "00000099000000000002"

    private fun tenant(oin: String) =
        TenantDefinition(
            oin = Oin(oin),
            naam = "Gemeente $oin",
            omgevingstype = Omgevingstype.TEST,
            domeinen = listOf("$oin.example"),
            thema = Thema(titel = "t"),
            inlogmiddelen = setOf(Inlogmiddel.TEST),
            authenticatie = Authenticatie("https://login.example/realms/$oin", "/k.jwk"),
            minimumNiveau = Betrouwbaarheidsniveau.SUBSTANTIEEL,
            sessie = Sessie(),
        )

    private fun directory(vararg oins: String) = TenantDirectory(oins.map(::tenant))

    @Test
    fun `een niet-gemigreerde module met een tenant is in orde`() {
        val guard = MeerdereTenantsGuard(directory(eerste), listOf(NietGemigreerdeModule("besluiten")))

        assertThat(guard.controleer()).isNull()
        assertThatCode { guard.afterPropertiesSet() }.doesNotThrowAnyException()
    }

    @Test
    fun `zonder niet-gemigreerde module mogen er meer tenants zijn`() {
        assertThat(MeerdereTenantsGuard(directory(eerste, tweede), emptyList()).controleer()).isNull()
    }

    @Test
    fun `een niet-gemigreerde module met meer tenants weigert en noemt de modules en de OIN's`() {
        val guard = MeerdereTenantsGuard(directory(tweede, eerste), listOf(NietGemigreerdeModule("openproduct"), NietGemigreerdeModule("besluiten"), NietGemigreerdeModule("besluiten")))

        assertThat(guard.controleer())
            .contains("'besluiten', 'openproduct'")
            .contains("$eerste, $tweede")
        assertThatThrownBy { guard.afterPropertiesSet() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'besluiten', 'openproduct'")
    }

    private fun tenantmap(vararg oins: String) {
        Files.writeString(dir.resolve("base.yaml"), "omgevingstype: test\ninlogmiddelen: [test]\n")
        oins.forEach {
            Files.writeString(
                dir.resolve("$it.yaml"),
                "oin: \"$it\"\nnaam: N\ndomeinen: [$it.example]\nthema:\n  titel: T\nauthenticatie:\n  issuer: https://login.example/realms/$it\n  sleutelBestand: /run/secrets/$it/k0.jwk\n",
            )
        }
    }

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenancyAutoConfiguration::class.java))
            .withPropertyValues("management.server.port=8091", "server.port=8080")

    @Test
    fun `de runtime start niet met twee tenants en een niet-gemigreerde module, wel met een tenant`() {
        tenantmap(eerste, tweede)
        val met = runner.withPropertyValues("nl-portal.tenancy.directory=$dir").withBean(NietGemigreerdeModule::class.java, { NietGemigreerdeModule("openklant") })

        met.run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasStackTraceContaining("'openklant'").hasStackTraceContaining(eerste).hasStackTraceContaining(tweede)
        }
        runner.withPropertyValues("nl-portal.tenancy.directory=$dir").run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `de runtime start met een tenant en een niet-gemigreerde module`() {
        tenantmap(eerste)

        runner
            .withPropertyValues("nl-portal.tenancy.directory=$dir")
            .withBean(NietGemigreerdeModule::class.java, { NietGemigreerdeModule("openklant") })
            .run { assertThat(it).hasNotFailed() }
    }
}