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
package nl.nlportal.besluiten.service

import kotlinx.coroutines.test.runTest
import nl.nlportal.besluiten.client.BesluitenApiClient
import nl.nlportal.besluiten.domain.BesluitDocument
import nl.nlportal.commonground.authentication.JwtBuilder
import nl.nlportal.documentenapi.domain.Document
import nl.nlportal.documentenapi.domain.DocumentStatus
import nl.nlportal.documentenapi.domain.Vertrouwelijkheid
import nl.nlportal.documentenapi.service.DocumentenApiService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** De documenten bij een besluit dragen de identificatie van de ingelogde vertegenwoordigde, niet die uit de bron. */
class BesluitenServiceTest {
    private val besluitenApiClient: BesluitenApiClient = mock()
    private val documentenApiService: DocumentenApiService = mock()
    private val service = BesluitenService(besluitenApiClient, documentenApiService)

    @Test
    fun `the documents of a besluit carry the identificatie of the vertegenwoordigde`() =
        runTest {
            whenever(besluitenApiClient.getBesluitDocumenten("besluit-url", null))
                .thenReturn(listOf(BesluitDocument(url = "u", informatieobject = INFORMATIEOBJECT, besluit = "besluit-url")))
            whenever(documentenApiService.getDocument(INFORMATIEOBJECT)).thenReturn(document())
            whenever(documentenApiService.filterDocuments(any())).thenAnswer { it.getArgument<List<Document>>(0) }

            val documenten = service.getBesluitDocumenten(JwtBuilder().vertegenwoordigdeKvk("12345678").buildAuthentication(), "besluit-url")

            assertEquals(listOf("12345678"), documenten.map { it.identificatie })
        }

    private fun document() =
        Document(
            url = INFORMATIEOBJECT,
            identificatie = "uit-de-bron",
            creatiedatum = "2024-01-01",
            titel = "titel",
            formaat = "application/pdf",
            bestandsnaam = "besluit.pdf",
            bestandsomvang = 1,
            status = DocumentStatus.DEFINITIEF,
            vertrouwelijkheidaanduiding = Vertrouwelijkheid.OPENBAAR,
        )

    private companion object {
        const val INFORMATIEOBJECT = "https://documenten.example.nl/api/v1/enkelvoudiginformatieobjecten/0727b025-eaae-4587-a375-3fe671a19dd8"
    }
}