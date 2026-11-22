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

import java.nio.file.Files
import java.nio.file.Path

/**
 * Eén bron per testbean (ticket #559): in de moduletests komen de beans `tenantDirectory`, `geheimenBron` en `vasteTenantWebFilter`
 * (en elke `@Bean`-methode van die types, en elke `withBean(<type>::class.java ...)`-registratie van die types) uit de gedeelde
 * testhulp `TestTenants` (testFixtures van `:core`), niet uit een eigen definitie per module. De tests van `:app` en `:app-dev`
 * zijn uitgezonderd. Bestaande overtreders staan in [BEKENDE_OVERTREDERS] als `bestand -> aantal definities` (ratchet): meer
 * definities dan bekend faalt, minder ook (de lijst moet krimpen).
 *
 * Beperkingen: tekstscan van `src/test` (alleen `.kt`, geen andere sourcesets en geen Java) op `@Bean`- en `withBean`-vormen; een
 * bean die op een andere manier wordt geregistreerd ontsnapt. Let op bij de Foutcodes-snapshot: `rest: MODULE_NOT_AVAILABLE` staat
 * er niet in, want REST geeft een ProblemDetail zonder code.
 */
object TestBeans {
    private val namen = setOf("tenantDirectory", "geheimenBron", "vasteTenantWebFilter")
    private val typen = setOf("TenantDirectory", "GeheimenBron", "VasteTenantWebFilter")
    private val typenAlternatief = typen.joinToString("|")
    private const val HAAKJES = """(?:[^()]|\([^()]*\))*"""

    private val beanStart =
        Regex(
            """@Bean\b(?:\s*\($HAAKJES\))?(?:\s*@[\w.]+(?:\s*\($HAAKJES\))?)*\s*(?:\w+\s+)*?fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(""",
        )
    private val retour = Regex("""\s*(?::\s*([\w.]+)|=\s*([\w.]+)\s*\()?""")
    private val withBean =
        Regex(
            """\.withBean(?:<(?:[\w.]+\.)?($typenAlternatief)>|\(\s*(?:"[^"]*"\s*,\s*)?(?:[\w.]+\.)?($typenAlternatief)::class)""",
        )

    private val uitgezonderdeModules = setOf("app", "app-dev")

    /** De bestanden (relatief aan de modulemap, met `/`) die een testbean zelf definiëren, met het aantal definities. */
    val BEKENDE_OVERTREDERS: Map<String, Int> =
        mapOf(
            "berichten/kotlin/nl/nlportal/berichten/BerichtenTestTenantConfiguratie.kt" to 3,
            "berichten/kotlin/nl/nlportal/berichten/autoconfigure/BerichtenAutoConfigurationTest.kt" to 1,
            "catalogi-api/kotlin/nl/nlportal/catalogiapi/autoconfigure/CatalogiApiAutoConfigurationTest.kt" to 3,
            "common-ground-authentication/kotlin/nl/nlportal/commonground/authentication/bff/BffTestOmgeving.kt" to 1,
            "common-ground-authentication/kotlin/nl/nlportal/commonground/authentication/bff/BffVereistTest.kt" to 1,
            "core/kotlin/nl/nlportal/core/koppeling/KoppelingenTest.kt" to 2,
            "core/kotlin/nl/nlportal/core/koppeling/TenantKoppelingenTest.kt" to 9,
            "core/kotlin/nl/nlportal/core/tenancy/TenancyAutoConfigurationTest.kt" to 1,
            "documenten-api/kotlin/nl/nlportal/documentenapi/autoconfigure/DocumentenApiAutoConfigurationTest.kt" to 3,
            "haalcentraal-brp/kotlin/nl/nlportal/haalcentraalbrp/autoconfiguration/HaalCentraalBrpAutoConfigurationTest.kt" to 1,
            "haalcentraal-brp/kotlin/nl/nlportal/haalcentraalbrp/graphql/HaalCentraalBrpQueryIT.kt" to 3,
            "kvk/kotlin/nl/nlportal/kvk/autoconfiguration/KvkAutoConfigurationTest.kt" to 3,
            "kvk/kotlin/nl/nlportal/kvk/graphql/KvkQueryIT.kt" to 3,
            "kvk/kotlin/nl/nlportal/kvk/graphql/KvkQueryZonderKoppelingIT.kt" to 3,
            "objectenapi/kotlin/nl/nlportal/zgw/objectenapi/autoconfiguration/ObjectsApiAutoConfigurationTest.kt" to 3,
            "openproduct/kotlin/nl/nlportal/openproduct/OpenProductTestTenantConfiguratie.kt" to 3,
            "payment-direct/kotlin/nl/nlportal/payment/direct/PaymentTestConfiguration.kt" to 3,
            "payment-direct/kotlin/nl/nlportal/payment/direct/autoconfiguration/DirectPaymentAutoConfigurationTest.kt" to 2,
            "taak/kotlin/nl/nlportal/zgw/taak/ObjectenTestServer.kt" to 3,
            "taak/kotlin/nl/nlportal/zgw/taak/autoconfigure/TaakAutoConfigurationTest.kt" to 1,
            "verificatie/kotlin/nl/nlportal/verificatie/autoconfigure/VerificatieAutoConfigurationTest.kt" to 7,
            "zaken-api/kotlin/nl/nlportal/zakenapi/autoconfigure/ZakenApiAutoConfigurationTest.kt" to 2,
            "zaken/kotlin/nl/nlportal/zaken/ZakenTestTenant.kt" to 3,
        )

    /** De overtredende definities in [tekst]: de naam van een `@Bean`-methode, of `withBean(<type>)`. */
    fun overtredingen(tekst: String): List<String> {
        val beans =
            beanStart.findAll(tekst).mapNotNull { m ->
                val naam = m.groupValues[1]
                val r = retour.matchAt(tekst, sluitHaakje(tekst, m.range.last))
                val type =
                    (r?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: r?.groupValues?.get(2).orEmpty())
                        .substringAfterLast('.')
                naam.takeIf { naam in namen || type in typen }?.let { m.range.first to it }
            }
        val registraties =
            withBean.findAll(tekst).map { m ->
                m.range.first to "withBean(${m.groupValues[1].ifEmpty { m.groupValues[2] }})"
            }
        return (beans + registraties).sortedBy { it.first }.map { it.second }.toList()
    }

    /** De index net na het haakje dat de openingshaak op [open] sluit (haakjes in strings tellen niet mee). */
    private fun sluitHaakje(
        tekst: String,
        open: Int,
    ): Int {
        var diepte = 0
        var instring = false
        for (i in open until tekst.length) {
            val c = tekst[i]
            when {
                c == '"' && tekst.getOrNull(i - 1) != '\\' -> {
                    instring = !instring
                }

                instring -> {}

                c == '(' -> {
                    diepte++
                }

                c == ')' -> {
                    if (--diepte == 0) return i + 1
                }
            }
        }
        return tekst.length
    }

    /** Alle testbestanden onder `src/test` van de [modules] die een testbean definiëren, als `<module>/<pad>` met het aantal definities. */
    fun overtreders(modules: List<Path>): Map<String, Int> =
        modules
            .filter { it.fileName.toString() !in uitgezonderdeModules }
            .flatMap { module ->
                val testmap = module.resolve("src/test")
                if (!Files.isDirectory(testmap)) {
                    emptyList()
                } else {
                    Files.walk(testmap).use { paden ->
                        paden
                            .filter { it.toString().endsWith(".kt") }
                            .map { it to overtredingen(Files.readString(it)).size }
                            .filter { it.second > 0 }
                            .map { (pad, aantal) ->
                                "${module.fileName}/${testmap.relativize(pad).toString().replace('\\', '/')}" to aantal
                            }.toList()
                    }
                }
            }.toMap()
}