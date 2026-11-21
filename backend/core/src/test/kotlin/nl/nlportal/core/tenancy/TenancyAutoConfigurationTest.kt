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
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** Seam: het opstarten van de runtime met een tenantmap; een ongeldige configuratie laat de context niet starten. */
class TenancyAutoConfigurationTest {
    @TempDir
    lateinit var dir: Path

    private val basis = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TenancyAutoConfiguration::class.java))

    private val runner = basis.withPropertyValues("management.server.port=8091", "server.port=8080")

    private fun geldigeTenant() {
        Files.writeString(dir.resolve("base.yaml"), "omgevingstype: test\ninlogmiddelen: [test]\n")
        Files.writeString(
            dir.resolve("00000099000000000001.yaml"),
            "oin: \"00000099000000000001\"\nnaam: N\ndomeinen: [a.example]\nthema:\n  titel: T\n",
        )
    }

    @Test
    fun `zonder tenantmap staat de tenancy uit`() {
        runner.run { assertThat(it).doesNotHaveBean(TenantDirectory::class.java).doesNotHaveBean(TenantWebFilter::class.java) }
    }

    @Test
    fun `een geldige tenantmap levert de directory en het filter`() {
        geldigeTenant()

        runner.withPropertyValues("nl-portal.tenancy.directory=$dir").run {
            assertThat(it).hasNotFailed()
            assertThat(it.getBean(TenantDirectory::class.java).forHost("a.example")?.oin).isEqualTo(Oin("00000099000000000001"))
            assertThat(it).hasSingleBean(TenantWebFilter::class.java)
        }
    }

    @Test
    fun `de runtime leest geheimen uit de omgeving, tenzij een eigen geheimenbron is geregistreerd`() {
        geldigeTenant()
        val omgeving = runner.withPropertyValues("nl-portal.tenancy.directory=$dir", "nl-portal.geheimen.zaken-sleutel=waarde")

        omgeving.run { assertThat(it.getBean(GeheimenBron::class.java).resolve(GeheimVerwijzing("zaken-sleutel"))).isEqualTo("waarde") }
        omgeving.withBean(GeheimenBron::class.java, { GeheimenBron { "eigen" } }).run {
            assertThat(it.getBean(GeheimenBron::class.java).resolve(GeheimVerwijzing("zaken-sleutel"))).isEqualTo("eigen")
        }
    }

    @Test
    fun `een ongeldig tenantbestand laat de context niet starten en noemt de fout`() {
        geldigeTenant()
        Files.writeString(
            dir.resolve("00000099000000000001.yaml"),
            "oin: \"00000099000000000001\"\nnaam: N\ndomeinen: [A.example]\nthema:\n  titel: T\n",
        )

        runner.withPropertyValues("nl-portal.tenancy.directory=$dir").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasStackTraceContaining("ONGELDIG_DOMEIN")
        }
    }

    @Test
    fun `een ontbrekende map laat de context niet starten`() {
        runner.withPropertyValues("nl-portal.tenancy.directory=${dir.resolve("bestaat-niet")}").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasStackTraceContaining("GEEN_TENANTBESTANDEN")
        }
    }

    @Test
    fun `zonder eigen managementpoort of met refresh in de exposure start de context niet`() {
        geldigeTenant()

        basis.withPropertyValues("nl-portal.tenancy.directory=$dir").run { assertThat(it).hasFailed() }
        basis
            .withPropertyValues(
                "nl-portal.tenancy.directory=$dir",
                "management.server.port=8091",
                "management.endpoints.web.exposure.include=health,refresh",
            ).run { assertThat(it).hasFailed() }
    }

    @Test
    fun `een ongeldig proxy-adres laat de context niet starten`() {
        geldigeTenant()

        runner.withPropertyValues("nl-portal.tenancy.directory=$dir", "nl-portal.tenancy.trusted-proxies=geen-adres").run {
            assertThat(it).hasFailed()
        }
    }

    @Test
    fun `een tenantmap die false heet of leeg is zet de tenancy niet stil uit maar weigert het opstarten`() {
        runner.withPropertyValues("nl-portal.tenancy.directory=false").run { assertThat(it).hasFailed() }
        runner.withPropertyValues("nl-portal.tenancy.directory=").run { assertThat(it).hasFailed() }
    }

    @Test
    fun `op Kubernetes zonder server-forward-headers-strategy start de context niet`() {
        geldigeTenant()

        runner
            .withPropertyValues("nl-portal.tenancy.directory=$dir", "spring.main.cloud-platform=kubernetes")
            .run { assertThat(it).hasFailed() }
        runner
            .withPropertyValues("nl-portal.tenancy.directory=$dir", "spring.main.cloud-platform=kubernetes", "server.forward-headers-strategy=none")
            .run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `een ForwardedHeaderTransformer in de context laat de context niet starten`() {
        geldigeTenant()

        runner
            .withPropertyValues("nl-portal.tenancy.directory=$dir")
            .withBean(org.springframework.web.server.adapter.ForwardedHeaderTransformer::class.java)
            .run { assertThat(it).hasFailed() }
    }
}