/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.documentenapi.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.util.ZgwUrls.extractId
import nl.nlportal.core.util.requireConfigured
import nl.nlportal.documentenapi.client.DocumentenApiClient
import nl.nlportal.documentenapi.configuratie.DocumentenApiConfiguratie
import nl.nlportal.documentenapi.configuratie.DocumentenApiTenantConfiguratie
import nl.nlportal.documentenapi.domain.Document
import nl.nlportal.documentenapi.domain.DocumentStatus
import nl.nlportal.documentenapi.domain.PostEnkelvoudiginformatieobjectRequest
import nl.nlportal.documentenapi.exceptions.MimeTypeDeniedException
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import org.apache.tika.Tika
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Documenten ophalen en uploaden voor de huidige tenant. De gegevens komen uit de moduleconfiguratie van die tenant
 * (`modules.documenten-api`), de verbinding uit de koppeling met de naam van de document-API.
 */
class DocumentenApiService(
    private val documentenApiClient: DocumentenApiClient,
    private val directory: TenantDirectory,
) {
    suspend fun getDocument(
        documentId: UUID,
        documentApi: String,
    ): Document = documentenApiClient.getDocument(documentId, documentApi)

    suspend fun getDocument(documentUrl: String): Document = documentenApiClient.getDocument(extractId(documentUrl), documentApiVoorUrl(documentUrl))

    suspend fun getDocumentContentStreaming(
        documentId: UUID,
        documentApi: String,
    ): Flow<DataBuffer> = documentenApiClient.getDocumentContentStream(documentId, documentApi)

    suspend fun getDocumentContentStreaming(informatieobejctUrl: String): Flow<DataBuffer> = documentenApiClient.getDocumentContentStream(extractId(informatieobejctUrl), documentApiVoorUrl(informatieobejctUrl))

    /** De koppeling waar een upload naartoe gaat als de aanroeper er geen kiest. */
    suspend fun standaardDocumentApi(): String = directory.module<DocumentenApiConfiguratie>().standaardDocumentApi

    suspend fun uploadDocument(
        content: ByteArray,
        filename: String,
        documentApi: String,
        informatieobjecttype: String? = null,
    ): Document {
        val auteur =
            ReactiveSecurityContextHolder
                .getContext()
                .map { (it.authentication as PortalAuthentication).identiteit.vertegenwoordigde.identificatie }
                .awaitSingleOrNull() ?: "valtimo"
        val configuratie = directory.module<DocumentenApiConfiguratie>()
        val documentApiConfiguratie =
            configuratie.documentApis[documentApi].requireConfigured(DocumentenApiTenantConfiguratie.NAAM, "documentApis.$documentApi")

        if (configuratie.toegestaneMimeTypes.isNotEmpty()) {
            val mediaType = Tika().detect(content).split(";")[0].trim()
            if (mediaType !in configuratie.toegestaneMimeTypes) {
                throw MimeTypeDeniedException("$mediaType is not allowed for uploads.")
            }
        }

        return documentenApiClient.postDocument(
            PostEnkelvoudiginformatieobjectRequest(
                bronorganisatie = documentApiConfiguratie.rsin.requireConfigured(DocumentenApiTenantConfiguratie.NAAM, "documentApis.$documentApi.rsin"),
                creatiedatum = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                titel = filename,
                auteur = auteur,
                status = DocumentStatus.DEFINITIEF,
                taal = "nld",
                bestandsnaam = filename,
                indicatieGebruiksrecht = false,
                informatieobjecttype =
                    informatieobjecttype
                        .takeUnless {
                            it.isNullOrEmpty()
                        }
                        ?: documentApiConfiguratie.documentTypeUrl.requireConfigured(DocumentenApiTenantConfiguratie.NAAM, "documentApis.$documentApi.documentTypeUrl"),
            ),
            content,
            documentApi,
        )
    }

    suspend fun filterDocuments(
        documents: List<Document>,
    ): List<Document> {
        val configuratie = directory.module<DocumentenApiConfiguratie>()
        return documents.filter { document ->
            document.status in configuratie.statusWitlijst &&
                document.vertrouwelijkheidaanduiding in configuratie.vertrouwelijkheidsaanduidingWitlijst
        }
    }

    private suspend fun documentApiVoorUrl(documentUrl: String): String = documentenApiClient.documentApiVoorUrl(documentUrl, directory.module<DocumentenApiConfiguratie>().documentApis.keys)
}