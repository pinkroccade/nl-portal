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
 * (en elke `@Bean`-methode van die types) uit de gedeelde testhulp `TestTenants` (testFixtures van `:core`), niet uit een eigen
 * definitie per module. De tests van `:app` en `:app-dev` zijn uitgezonderd. Bestaande overtreders staan in [BEKENDE_OVERTREDERS]
 * (ratchet): een nieuwe overtreder faalt, en een overtreder die is opgeruimd moet uit de lijst.
 */
object TestBeans {
    private val namen = setOf("tenantDirectory", "geheimenBron", "vasteTenantWebFilter")
    private val typen = setOf("TenantDirectory", "GeheimenBron", "VasteTenantWebFilter")
    private val beanMethode =
        Regex("""@Bean[^\n]*\n(?:\s*@[^\n]*\n)*\s*(?:open\s+)?fun\s+(\w+)\s*\([^)]*\)\s*(?::\s*(\w+)|=\s*(\w+)\()?""")

    private val uitgezonderdeModules = setOf("app", "app-dev")

    /** De bestanden (relatief aan de modulemap, met `/`) die een testbean zelf definiëren. */
    val BEKENDE_OVERTREDERS =
        setOf(
            "berichten/kotlin/nl/nlportal/berichten/BerichtenTestTenantConfiguratie.kt",
            "common-ground-authentication/kotlin/nl/nlportal/commonground/authentication/bff/BffTestOmgeving.kt",
            "haalcentraal-brp/kotlin/nl/nlportal/haalcentraalbrp/autoconfiguration/HaalCentraalBrpAutoConfigurationTest.kt",
            "haalcentraal-brp/kotlin/nl/nlportal/haalcentraalbrp/graphql/HaalCentraalBrpQueryIT.kt",
            "kvk/kotlin/nl/nlportal/kvk/graphql/KvkQueryIT.kt",
            "kvk/kotlin/nl/nlportal/kvk/graphql/KvkQueryZonderKoppelingIT.kt",
            "openproduct/kotlin/nl/nlportal/openproduct/OpenProductTestTenantConfiguratie.kt",
            "payment-direct/kotlin/nl/nlportal/payment/direct/PaymentTestConfiguration.kt",
            "taak/kotlin/nl/nlportal/zgw/taak/ObjectenTestServer.kt",
            "zaken/kotlin/nl/nlportal/zaken/ZakenTestTenant.kt",
        )

    /** De namen van de overtredende `@Bean`-methoden in [tekst]. */
    fun overtredingen(tekst: String): List<String> =
        beanMethode
            .findAll(tekst)
            .filter { m -> m.groupValues[1] in namen || m.groupValues[2] in typen || m.groupValues[3] in typen }
            .map { it.groupValues[1] }
            .toList()

    /** Alle testbestanden onder `src/test` van de [modules] die een testbean definiëren, als `<module>/<pad>`. */
    fun overtreders(modules: List<Path>): Set<String> =
        modules
            .filter { it.fileName.toString() !in uitgezonderdeModules }
            .flatMap { module ->
                val testmap = module.resolve("src/test")
                if (!Files.isDirectory(testmap)) {
                    emptyList()
                } else {
                    Files.walk(testmap).use { paden ->
                        paden
                            .filter { it.toString().endsWith(".kt") && overtredingen(Files.readString(it)).isNotEmpty() }
                            .map { "${module.fileName}/${testmap.relativize(it).toString().replace('\\', '/')}" }
                            .toList()
                    }
                }
            }.toSet()
}