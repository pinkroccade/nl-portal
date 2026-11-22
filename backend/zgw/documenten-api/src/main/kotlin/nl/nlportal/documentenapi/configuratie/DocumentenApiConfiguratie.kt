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

import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.NietErfbaar
import nl.nlportal.core.tenancy.TenantModuleConfiguratie
import nl.nlportal.documentenapi.domain.DocumentStatus
import nl.nlportal.documentenapi.domain.Vertrouwelijkheid
import java.net.URI

/**
 * De gegevens van de Documenten API voor een tenant, in het tenantbestand onder `modules.documenten-api`. De verbinding met elke
 * documenten-API (URL, authenticatie) staat als koppeling onder `koppelingen`; de sleutel van [documentApis] is de naam van die koppeling.
 */
data class DocumentenApiConfiguratie(
    /** De koppeling waar een upload naartoe gaat als de aanroeper er geen kiest. */
    @NietErfbaar val standaardDocumentApi: String,
    @NietErfbaar val documentApis: Map<String, DocumentApiConfiguratie>,
    /** Leeg: elk type is toegestaan. */
    val toegestaneMimeTypes: Set<String> = emptySet(),
    val vertrouwelijkheidsaanduidingWitlijst: List<Vertrouwelijkheid> =
        listOf(
            Vertrouwelijkheid.OPENBAAR,
            Vertrouwelijkheid.BEPERKT_OPENBAAR,
            Vertrouwelijkheid.INTERN,
            Vertrouwelijkheid.ZAAKVERTROUWELIJK,
        ),
    val statusWitlijst: List<DocumentStatus> = listOf(DocumentStatus.DEFINITIEF, DocumentStatus.GEARCHIVEERD),
)

/** De gegevens bij één documenten-API voor uploads; de koppeling met dezelfde naam bepaalt waar de API staat. */
data class DocumentApiConfiguratie(
    @NietErfbaar val rsin: String? = null,
    @NietErfbaar val documentTypeUrl: String? = null,
)

/** Draagt [DocumentenApiConfiguratie] bij aan het tenantbestand (T5-haakpunt) en bewaakt dat elke document-API een koppeling heeft. */
class DocumentenApiTenantConfiguratie : TenantModuleConfiguratie<DocumentenApiConfiguratie> {
    override val naam = NAAM
    override val klasse = DocumentenApiConfiguratie::class

    override fun valideer(
        configuratie: DocumentenApiConfiguratie,
        context: ModuleContext,
    ): List<ModuleOvertreding> =
        buildList {
            if (configuratie.documentApis.isEmpty()) add(ModuleOvertreding("documentApis", "minstens een document-API"))
            if (configuratie.standaardDocumentApi !in configuratie.documentApis) {
                add(ModuleOvertreding("standaardDocumentApi", "moet een van de sleutels van documentApis zijn"))
            }
            configuratie.documentApis.keys.filter { it !in context.koppelingen }.forEach {
                add(ModuleOvertreding("documentApis.$it", "er is geen koppeling met deze naam onder koppelingen"))
            }
            addAll(overlappendeBasisUrls(configuratie, context))
        }

    /** Twee document-API's mogen niet op dezelfde basis-URL of een prefix op segmentgrens van elkaar staan: een document-URL valt dan onder allebei. */
    private fun overlappendeBasisUrls(
        configuratie: DocumentenApiConfiguratie,
        context: ModuleContext,
    ): List<ModuleOvertreding> {
        val basis = configuratie.documentApis.keys.mapNotNull { naam -> context.koppelingUrls[naam]?.let { naam to segmenten(it) } }
        return basis
            .filter { (naam, eigen) -> basis.any { (ander, andere) -> ander != naam && eigen.size >= andere.size && eigen.take(andere.size) == andere } }
            .map { (naam, _) -> ModuleOvertreding("documentApis.$naam", "de basis-URL van de koppeling valt samen met of binnen die van een andere document-API") }
    }

    /** Schema, host, poort (standaardpoort weggelaten) en pad per segment; een niet te lezen URL geeft een eigen, nooit overlappende lijst. */
    private fun segmenten(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf("ongeldig:$url")
        val schema = uri.scheme?.lowercase().orEmpty()
        val poort = uri.port.takeUnless { it == -1 || (schema == "https" && it == 443) || (schema == "http" && it == 80) }
        return listOf(schema, uri.host?.lowercase().orEmpty(), poort?.toString().orEmpty()) +
            uri.path
                .orEmpty()
                .split('/')
                .filter { it.isNotEmpty() }
    }

    companion object {
        /** De sleutel onder `modules`, ook de modulenaam bij het opvragen van een koppeling. */
        const val NAAM = "documenten-api"
    }
}