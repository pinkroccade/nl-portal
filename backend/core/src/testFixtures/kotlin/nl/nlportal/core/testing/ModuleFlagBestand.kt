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
package nl.nlportal.core.testing

import nl.nlportal.core.modulegate.ModuleFlag
import nl.nlportal.core.modulegate.ModuleGate
import nl.nlportal.core.tenancy.Oin
import org.awaitility.Awaitility.await
import org.springframework.test.context.DynamicPropertyRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Het flagd-bestand van de tests van een module op de gate. OpenFeature is een JVM-brede singleton: laat alle testcontexts van een module
 * hetzelfde bestand gebruiken (één [ModuleFlagBestand] in de `companion object`) in plaats van elk een eigen bestand. Alle flags staan standaard
 * aan voor elke tenant; `zet` schakelt er één uit of weer aan, voor alle tenants of alleen voor [alleenVoor].
 */
class ModuleFlagBestand(
    private val modules: List<ModuleFlag>,
) {
    private val bestand: Path = Files.createTempFile("module-flags", ".flagd.json")
    private val staat = modules.associateWith<ModuleFlag, Set<Oin>?> { null }.toMutableMap()

    init {
        schrijf()
    }

    /** Zet het bestand en een korte pollinterval als property van de testcontext (in een `@DynamicPropertySource`). */
    fun registreer(registry: DynamicPropertyRegistry) {
        registry.add("nl-portal.feature-flags.file") { bestand.toString() }
        registry.add("nl-portal.feature-flags.poll-interval-ms") { 200 }
    }

    /**
     * Zet de flag van [module]: aan voor [alleenVoor] (en uit voor de rest) als dat is gegeven, anders voor elke tenant aan of uit
     * ([aan]). Wacht tot de draaiende gate de wijziging ziet voor [wacht], standaard de eerste tenant.
     */
    fun zet(
        module: ModuleFlag,
        aan: Boolean,
        gate: ModuleGate,
        alleenVoor: Set<Oin>? = null,
        wacht: Oin = TestTenants.BOSRAND,
    ) {
        staat[module] = alleenVoor ?: if (aan) null else emptySet()
        schrijf()
        val verwacht = alleenVoor?.contains(wacht) ?: aan
        await().atMost(Duration.ofSeconds(10)).until { gate.beoordeel(module, wacht).flag == verwacht }
    }

    /** Zet elke flag weer aan voor elke tenant. */
    fun herstel(gate: ModuleGate) = modules.forEach { zet(it, true, gate) }

    private fun schrijf() {
        val flags =
            modules.joinToString(", ") { module ->
                val voor = staat[module]
                val oins = voor?.joinToString { oin -> "\"${oin.value}\"" }
                val targeting = if (voor == null) "" else """, "targeting": { "if": [{ "in": [{ "var": "tenant" }, [$oins]] }, "on", "off"] }"""
                val standaard = if (voor == null) "on" else "off"
                """"${module.flag.key}": { "state": "ENABLED", "variants": { "on": true, "off": false }, "defaultVariant": "$standaard"$targeting }"""
            }
        Files.writeString(bestand, """{ "${'$'}schema": "https://flagd.dev/schema/v0/flags.json", "flags": { $flags } }""")
    }
}