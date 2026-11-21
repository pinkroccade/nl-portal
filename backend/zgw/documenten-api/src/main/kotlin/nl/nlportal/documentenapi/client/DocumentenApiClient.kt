/*
 * Copyright 2015-2024 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.documentenapi.client

import kotlinx.coroutines.flow.Flow
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.get
import nl.nlportal.core.koppeling.post
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.documentenapi.configuratie.DocumentenApiTenantConfiguratie
import nl.nlportal.documentenapi.domain.Document
import nl.nlportal.documentenapi.domain.PostEnkelvoudiginformatieobjectRequest
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.MediaType
import java.util.Base64
import java.util.UUID

/**
 * De aanroepen naar een documenten-API. Elke aanroep haalt de koppeling van de huidige tenant op bij het register
 * (`koppeling` is de naam van de koppeling in het tenantbestand); er is geen eigen HTTP-client en er wordt er niet een bewaard.
 */
class DocumentenApiClient(
    private val register: KoppelingRegister,
) {
    suspend fun getDocument(
        id: UUID,
        documentApi: String,
    ): Document {
        val document: Document = koppeling(documentApi).get("/enkelvoudiginformatieobjecten/$id", headers = CRS)
        document.documentapi = documentApi
        return document
    }

    /** De inhoud als stroom; de aanroeper geeft de buffers vrij (of geeft ze door aan een antwoord dat dat doet). */
    suspend fun getDocumentContentStream(
        id: UUID,
        documentApi: String,
    ): Flow<DataBuffer> = koppeling(documentApi).getStream("/enkelvoudiginformatieobjecten/$id/download", MediaType.APPLICATION_OCTET_STREAM, headers = CRS)

    suspend fun postDocument(
        request: PostEnkelvoudiginformatieobjectRequest,
        documentContent: ByteArray,
        documentApi: String,
    ): Document {
        request.inhoud = Base64.getEncoder().encodeToString(documentContent)
        return koppeling(documentApi).post("/enkelvoudiginformatieobjecten", request, headers = CRS + ("Accept" to MediaType.APPLICATION_JSON_VALUE))
    }

    /**
     * De document-API (koppelingnaam) waar [documentUrl] onder valt: de URL moet onder de basis-URL van precies een van [documentApis]
     * van de huidige tenant vallen (schema, host, poort en pad op segmentgrens), niet alleen de basis-URL bevatten.
     */
    suspend fun documentApiVoorUrl(
        documentUrl: String,
        documentApis: Set<String>,
    ): String =
        documentApis.filter { koppeling(it).beheertUrl(documentUrl) }.singleOrNull()
            ?: throw ModuleNotConfiguredException(DocumentenApiTenantConfiguratie.NAAM, "document-API voor een document-URL")

    private suspend fun koppeling(documentApi: String) = register.current(DocumentenApiTenantConfiguratie.NAAM, documentApi)

    private companion object {
        val CRS = mapOf("Accept-Crs" to "EPSG:4326", "Content-Crs" to "EPSG:4326")
    }
}