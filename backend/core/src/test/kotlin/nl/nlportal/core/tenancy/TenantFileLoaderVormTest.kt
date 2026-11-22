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

import nl.nlportal.core.tenancy.TenantFileViolation.Code
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path

/** Seam: de vorm van elke waarde in een tenantbestand en het lezen van de bestanden zelf (pad, bestandsvorm, YAML-grenzen). */
class TenantFileLoaderVormTest {
    @TempDir
    lateinit var dir: Path

    private val bosrand = "00000099000000000001"
    private val basis = "omgevingstype: acceptatie\ninlogmiddelen: [digid]\n"
    private val modules = TenantModules.van(listOf(ProefModule()))

    private fun schrijf(
        naam: String,
        inhoud: String,
    ) = Files.writeString(dir.resolve(naam), inhoud.trimIndent())

    private fun tenant(extra: String = "") = "oin: \"$bosrand\"\nnaam: Gemeente\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n$extra\n"

    private fun laad() = TenantFileLoader(meerdereTenantsToegestaan = false, modules = modules).load(dir)

    private fun proef(regels: String) = tenant("modules:\n  proef:\n    brpVelden: [naam]\n" + regels.prependIndent("    "))

    private fun enige(extra: String): TenantFileViolation {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", extra)
        val result = laad()
        assertThat(result.violations).hasSize(1)
        return result.violations.single()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "actief: ja|modules.proef.actief",
            "actief: 1|modules.proef.actief",
            "maximum: veel|modules.proef.maximum",
            "maximum: 1.5|modules.proef.maximum",
            "grens: true|modules.proef.grens",
            "timeout: 5|modules.proef.timeout",
            "geheim: 5|modules.proef.geheim",
            "klantId: [a]|modules.proef.klantId",
            "steden: tekst|modules.proef.steden",
            "profielen: [1]|modules.proef.profielen",
            "profielen: tekst|modules.proef.profielen",
            "profielen: {standaard: 5}|modules.proef.profielen.standaard",
            "profielen: {standaard: {apiKey: BOSRAND.sleutel, naam: 5}}|modules.proef.profielen.standaard.naam",
        ],
    )
    fun `een waarde van de verkeerde vorm is ongeldig, op het pad van die waarde`(
        regel: String,
        pad: String,
    ) {
        val overtreding = enige(proef(regel.replace("BOSRAND", bosrand)))

        assertThat(overtreding.code).isEqualTo(Code.ONGELDIGE_WAARDE)
        assertThat(overtreding.pad).isEqualTo(pad)
        assertThat(overtreding.bestand).isEqualTo("$bosrand.yaml")
    }

    @Test
    fun `een module die geen map is, is ongeldig`() {
        val overtreding = enige(tenant("modules:\n  proef: tekst"))

        assertThat(overtreding.code).isEqualTo(Code.ONGELDIGE_WAARDE)
        assertThat(overtreding.pad).isEqualTo("modules.proef")
    }

    @Test
    fun `een element van een lijst heeft de vorm van het lijsttype`() {
        val overtreding = enige(tenant("modules:\n  proef:\n    brpVelden: [naam, 5]"))

        assertThat(overtreding.code).isEqualTo(Code.ONGELDIGE_WAARDE)
        assertThat(overtreding.pad).isEqualTo("modules.proef.brpVelden[]")
    }

    @Test
    fun `een opsomming weigert een onbekende waarde en een lege tekst is niet toegestaan`() {
        assertThat(enige(proef("niveau: MIDDEN")).bericht).contains("Geen toegestane waarde", "LAAG", "HOOG")
        assertThat(enige(proef("profielen: {standaard: {apiKey: $bosrand.sleutel, naam: \"  \"}}")).bericht).contains("Een lege waarde is niet toegestaan")
    }

    @Test
    fun `een geheim in platte tekst is geen verwijzing`() {
        val overtreding = enige(proef("geheim: plat wachtwoord"))

        assertThat(overtreding.code).isEqualTo(Code.GEHEIM_NIET_ALS_VERWIJZING)
        assertThat(overtreding.pad).isEqualTo("modules.proef.geheim")
    }

    @Test
    fun `een leeg optioneel veld is geen fout`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", proef("klantId:\ngeheim:\n"))

        assertThat(laad().violations).isEmpty()
    }

    @Test
    fun `een onbekende eigenschap in de basis wordt gemeld op het basisbestand`() {
        schrijf("base.yaml", basis + "onzin: 1\n")
        schrijf("$bosrand.yaml", tenant())

        val result = laad()

        assertThat(result.violations.map { it.code }).contains(Code.ONBEKENDE_EIGENSCHAP)
        assertThat(result.violations.first { it.code == Code.ONBEKENDE_EIGENSCHAP }.bestand).isEqualTo("base.yaml")
        assertThat(result.violations.first { it.code == Code.ONBEKENDE_EIGENSCHAP }.pad).isEqualTo("onzin")
    }

    @Test
    fun `alleen gewone yaml- en yml-bestanden tellen als tenantbestand`() {
        schrijf("base.yaml", basis)
        Files.createDirectory(dir.resolve("map.yaml"))
        schrijf("notities.txt", "oin: kapot: [")
        schrijf("$bosrand.yml", tenant())

        val result = laad()

        assertThat(result.violations).isEmpty()
        assertThat(result.tenants).hasSize(1)
    }

    @Test
    fun `een YAML-fout noemt het regelnummer`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", "oin: \"$bosrand\"\nnaam: [kapot\n")

        val overtreding = laad().violations.single()

        assertThat(overtreding.code).isEqualTo(Code.ONLEESBAAR)
        assertThat(overtreding.bericht).contains("Geen geldige YAML (regel ")
        val regel = Regex("regel ([0-9]+)").find(overtreding.bericht)!!.groupValues[1].toInt()
        assertThat(regel).isBetween(2, 3)
    }

    @Test
    fun `een dubbele sleutel is onleesbaar`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant().replace("naam: Gemeente", "naam: Gemeente\nnaam: Anders"))

        assertThat(laad().violations.map { it.code }).containsExactly(Code.ONLEESBAAR)
    }

    @Test
    fun `te veel verwijzingen naar een gedeelde lijst zijn onleesbaar`() {
        schrijf("base.yaml", basis)
        val verwijzingen = (1..12).joinToString("\n") { "r$it: *a" }
        schrijf("$bosrand.yaml", "a: &a [1]\n$verwijzingen\n" + tenant())

        assertThat(laad().violations.map { it.code }).containsExactly(Code.ONLEESBAAR)
    }

    @Test
    fun `een bestand boven de grootte van 64 KiB is onleesbaar`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant().replace("naam: Gemeente", "naam: " + "x".repeat(64 * 1024)))

        assertThat(laad().violations.map { it.code }).containsExactly(Code.ONLEESBAAR)
    }

    @Test
    fun `een sleutel die geen tekst is, is ongeldig`() {
        schrijf("base.yaml", basis)
        schrijf("$bosrand.yaml", tenant() + "1: a\n")

        val overtreding = laad().violations.single()

        assertThat(overtreding.code).isEqualTo(Code.ONGELDIGE_WAARDE)
        assertThat(overtreding.bericht).contains("Elke sleutel is tekst")
    }
}