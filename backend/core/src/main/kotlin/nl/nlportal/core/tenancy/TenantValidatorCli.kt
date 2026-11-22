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

import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * De validator als commandoregelprogramma (releaseartefact `nl-portal-tenant-validator.jar`), voor CI van een publieke
 * of private config-repository. Dezelfde regels als bij het opstarten van de runtime: [TenantFileLoader].
 *
 * Gebruik: `java -jar nl-portal-tenant-validator.jar <map> [--meerdere-tenants] [--sleutelmap=<map>]` of `... --schema` voor het JSON-schema.
 * Met `--sleutelmap` (de waarde van `nl-portal.sleutelmap`) controleert hij ook dat het sleutelbestand van elke tenant bestaat, in de map van die
 * tenant ligt en een bruikbare clientsleutel is; zonder de optie (CI van een config-repository zonder geheimen) controleert hij alleen het pad.
 * Exitcode 0 = geldig, 1 = ongeldig, 2 = verkeerd gebruik.
 */
object TenantValidatorCli {
    fun run(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        if (args == listOf("--schema")) {
            out.print(TenantSchemaGenerator.generate())
            return 0
        }
        val sleutelmap = args.firstOrNull { it.startsWith(SLEUTELMAP) }?.removePrefix(SLEUTELMAP)
        val opties = args.filter { it.startsWith("--") && !it.startsWith(SLEUTELMAP) }
        val paden = args.filterNot { it.startsWith("--") }
        if (paden.size != 1 || (opties - "--meerdere-tenants").isNotEmpty() || args.count { it.startsWith(SLEUTELMAP) } > 1 || sleutelmap?.isBlank() == true) {
            err.println("Gebruik: nl-portal-tenant-validator <map met tenantbestanden> [--meerdere-tenants] [--sleutelmap=<map>] | --schema (modules: zet hun jars op het klassenpad met java -cp)")
            return 2
        }
        val result = TenantFileLoader(meerdereTenantsToegestaan = "--meerdere-tenants" in opties, sleutelmap = sleutelmap?.let { Path.of(it) }).load(Path.of(paden.single()))
        if (result.violations.isEmpty()) {
            out.println("OK: ${result.tenants.size} tenant(s): ${result.tenants.joinToString { it.oin.value }}")
            return 0
        }
        result.violations.forEach { err.println("FOUT: $it") }
        return 1
    }

    private const val SLEUTELMAP = "--sleutelmap="

    @JvmStatic
    fun main(args: Array<String>): Unit = exitProcess(run(args.toList(), System.out, System.err))
}