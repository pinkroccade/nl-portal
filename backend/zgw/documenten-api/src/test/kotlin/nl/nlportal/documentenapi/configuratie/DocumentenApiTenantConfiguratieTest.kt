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
package nl.nlportal.documentenapi.configuratie

import nl.nlportal.core.tenancy.TenantFileLoader
import nl.nlportal.core.tenancy.TenantFileResult
import nl.nlportal.core.tenancy.TenantFileViolation.Code
import nl.nlportal.core.tenancy.TenantModules
import nl.nlportal.documentenapi.domain.DocumentStatus
import nl.nlportal.documentenapi.domain.Vertrouwelijkheid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Het tenantbestand voor de Documenten API: laden, standaardwaarden en de regels van `valideer` (story 13 en 14 van epic #499). */
class DocumentenApiTenantConfiguratieTest {
    @TempDir
    lateinit var dir: Path

    private val bosrand = "00000099000000000001"
    private val modules = TenantModules.van(listOf(DocumentenApiTenantConfiguratie()))

    private val koppelingen =
        """
        koppelingen:
          openzaak:
            url: https://open-zaak.example/documenten/api/v1
            auth:
              type: zgw-jwt
              clientId: nl-portal
              secret: $bosrand.documenten-secret
          archief:
            url: https://archief.example/documenten/api/v1
            auth:
              type: zgw-jwt
              clientId: nl-portal
              secret: $bosrand.archief-secret
        """.trimIndent()

    private fun laad(modulesBlok: String): TenantFileResult {
        Files.writeString(dir.resolve("base.yaml"), "omgevingstype: acceptatie\ninlogmiddelen: [digid]\n")
        Files.writeString(
            dir.resolve("$bosrand.yaml"),
            "oin: \"$bosrand\"\nnaam: Gemeente\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n$koppelingen\nmodules:\n  documenten-api:\n" +
                modulesBlok.trimIndent().prependIndent("    ") + "\n",
        )
        return TenantFileLoader(modules = modules).load(dir)
    }

    @Test
    fun `een volledige configuratie wordt getypeerd gelezen met de standaardwitlijsten`() {
        val result =
            laad(
                """
                standaardDocumentApi: openzaak
                documentApis:
                  openzaak:
                    rsin: "051845623"
                    documentTypeUrl: https://catalogi.example/type
                  archief: {}
                """,
            )

        assertThat(result.violations).isEmpty()
        val configuratie = result.tenants.single().requireModule<DocumentenApiConfiguratie>()
        assertThat(configuratie.standaardDocumentApi).isEqualTo("openzaak")
        assertThat(configuratie.documentApis.keys).containsExactlyInAnyOrder("openzaak", "archief")
        assertThat(configuratie.documentApis.getValue("openzaak")).isEqualTo(DocumentApiConfiguratie("051845623", "https://catalogi.example/type"))
        assertThat(configuratie.documentApis.getValue("archief")).isEqualTo(DocumentApiConfiguratie())
        assertThat(configuratie.toegestaneMimeTypes).isEmpty()
        assertThat(configuratie.vertrouwelijkheidsaanduidingWitlijst)
            .containsExactly(Vertrouwelijkheid.OPENBAAR, Vertrouwelijkheid.BEPERKT_OPENBAAR, Vertrouwelijkheid.INTERN, Vertrouwelijkheid.ZAAKVERTROUWELIJK)
        assertThat(configuratie.statusWitlijst).containsExactly(DocumentStatus.DEFINITIEF, DocumentStatus.GEARCHIVEERD)
    }

    @Test
    fun `witlijsten en mimetypes gebruiken de spelling van de Documenten API`() {
        val result =
            laad(
                """
                standaardDocumentApi: openzaak
                documentApis:
                  openzaak: {}
                toegestaneMimeTypes: [application/pdf, text/plain]
                vertrouwelijkheidsaanduidingWitlijst: [openbaar, zaakvertrouwelijk, zeer_geheim]
                statusWitlijst: [ter_vaststelling, definitief]
                """,
            )

        assertThat(result.violations).isEmpty()
        val configuratie = result.tenants.single().requireModule<DocumentenApiConfiguratie>()
        assertThat(configuratie.toegestaneMimeTypes).containsExactlyInAnyOrder("application/pdf", "text/plain")
        assertThat(configuratie.vertrouwelijkheidsaanduidingWitlijst)
            .containsExactly(Vertrouwelijkheid.OPENBAAR, Vertrouwelijkheid.ZAAKVERTROUWELIJK, Vertrouwelijkheid.ZEER_GEHEIM)
        assertThat(configuratie.statusWitlijst).containsExactly(DocumentStatus.TER_VASTSTELLING, DocumentStatus.DEFINITIEF)
    }

    @Test
    fun `een onbekende spelling in een witlijst weigert`() {
        val result = laad("standaardDocumentApi: openzaak\ndocumentApis:\n  openzaak: {}\nstatusWitlijst: [DEFINITIEF]")

        assertThat(result.violations).isNotEmpty()
        assertThat(result.violations.map { it.pad }).anyMatch { it.orEmpty().startsWith("modules.documenten-api.statusWitlijst") }
    }

    @Test
    fun `de standaard document-API moet een van de document-APIs zijn`() {
        val result = laad("standaardDocumentApi: elders\ndocumentApis:\n  openzaak: {}")

        assertThat(result.violations.map { it.code }).containsExactly(Code.MODULE_CONFIGURATIE_ONGELDIG)
        assertThat(result.violations.single().pad).isEqualTo("modules.documenten-api.standaardDocumentApi")
        assertThat(result.violations.single().bericht).startsWith("Tenant $bosrand").doesNotContain("elders")
    }

    @Test
    fun `een document-API zonder koppeling met dezelfde naam weigert het opstarten met de OIN`() {
        val result = laad("standaardDocumentApi: openzaak\ndocumentApis:\n  openzaak: {}\n  zonder-koppeling: {}")

        assertThat(result.violations.map { it.code }).containsExactly(Code.MODULE_CONFIGURATIE_ONGELDIG)
        assertThat(result.violations.single().pad).isEqualTo("modules.documenten-api.documentApis.zonder-koppeling")
        assertThat(result.violations.single().bericht).startsWith("Tenant $bosrand")
    }

    @Test
    fun `zonder document-API weigert het opstarten`() {
        val result = laad("standaardDocumentApi: openzaak\ndocumentApis: {}")

        assertThat(result.violations.map { it.code }).containsOnly(Code.MODULE_CONFIGURATIE_ONGELDIG)
        assertThat(result.violations.map { it.bericht }).anyMatch { it.contains("documentApis") }
    }

    @Test
    fun `een verplicht veld dat ontbreekt weigert`() {
        val result = laad("documentApis:\n  openzaak: {}")

        assertThat(result.violations.map { it.code }).contains(Code.VERPLICHTE_WAARDE_ONTBREEKT)
    }

    @Test
    fun `gemeentegebonden instellingen uit base-yaml worden geweigerd`() {
        Files.writeString(
            dir.resolve("base.yaml"),
            "omgevingstype: acceptatie\ninlogmiddelen: [digid]\nmodules:\n  documenten-api:\n    standaardDocumentApi: openzaak\n    documentApis:\n      openzaak:\n        rsin: \"051845623\"\n",
        )
        Files.writeString(
            dir.resolve("$bosrand.yaml"),
            "oin: \"$bosrand\"\nnaam: Gemeente\ndomeinen: [bosrand.example]\nthema:\n  titel: De Bosrand\n$koppelingen\n",
        )

        val result = TenantFileLoader(modules = modules).load(dir)

        assertThat(result.violations.map { it.code }).contains(Code.NIET_ERFBARE_WAARDE_IN_BASIS)
    }

    @Test
    fun `de module draagt zich zelf aan via de service loader`() {
        val gevonden = TenantModules.ontdek().configuratie("documenten-api")

        assertThat(gevonden).isInstanceOf(DocumentenApiTenantConfiguratie::class.java)
        assertThat(gevonden!!.klasse).isEqualTo(DocumentenApiConfiguratie::class)
    }
}