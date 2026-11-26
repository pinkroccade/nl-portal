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
package nl.nlportal.zgw.taak.web.rest

import nl.nlportal.core.modulegate.ModuleEndpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactive.awaitSingle
import nl.nlportal.core.koppeling.KoppelingConfiguratieException
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.documentenapi.domain.VirusScanStatus
import nl.nlportal.documentenapi.exceptions.MimeTypeDeniedException
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.documentenapi.service.VirusScanService
import nl.nlportal.documentenapi.util.FilenameSanitizer
import nl.nlportal.zgw.taak.service.TaakService
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@ModuleEndpoint("taak")
@RestController
@RequestMapping(value = ["/api/taak"])
class TaakDocumentResource(
    private val taakService: TaakService,
    private val documentenApiService: DocumentenApiService,
    private val virusScanService: VirusScanService?,
) {
    @PostMapping(
        value = ["/{taakId}/document/content"],
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    suspend fun uploadDocument(
        @PathVariable taakId: UUID,
        @RequestPart("file") file: FilePart,
        @RequestPart("informatieobjecttype", required = false) informatieobjecttype: String?,
        authentication: PortalAuthentication,
    ): ResponseEntity<Any> {
        taakService.getTaakByIdV2(taakId, authentication)

        val sanitizedFilename = FilenameSanitizer.sanitize(file.filename())
        val bufferedContent = bufferFileContent(file)

        val virusScanResult = virusScanService?.scan(bufferedContent)
        if (virusScanResult?.status == VirusScanStatus.VIRUS_FOUND) {
            return ResponseEntity(virusScanResult, HttpStatus.BAD_REQUEST)
        }

        return try {
            ResponseEntity.ok(
                documentenApiService.uploadDocument(
                    bufferedContent,
                    sanitizedFilename,
                    documentenApiService.standaardDocumentApi(),
                    informatieobjecttype,
                ),
            )
        } catch (e: MimeTypeDeniedException) {
            ResponseEntity(mapOf("code" to "MIME_TYPE_NIET_TOEGESTAAN"), HttpStatus.BAD_REQUEST)
        } catch (e: KoppelingException) {
            // Geen e.message naar de browser: alleen dezelfde code en sleutel als GraphQL, zodat de beheerder de log vindt.
            ResponseEntity(mapOf("code" to "KOPPELING_FOUT", "correlatieId" to e.correlatieId), HttpStatus.BAD_GATEWAY)
        } catch (e: ModuleNotConfiguredException) {
            intern(e, "MODULE_NIET_GECONFIGUREERD")
        } catch (e: TenantContextMissingException) {
            intern(e, "TENANT_ONTBREEKT")
        } catch (e: KoppelingConfiguratieException) {
            intern(e, "KOPPELING_FOUT")
        }
    }

    /** Als `GraphQLExceptionHandler.intern`: de melding bevat interne gegevens en gaat alleen de log in, de browser krijgt code en correlatieId. */
    private fun intern(
        e: Throwable,
        code: String,
    ): ResponseEntity<Any> {
        val correlatieId = UUID.randomUUID().toString()
        log.warn { "Interne fout bij een documentupload ($code, ${e::class.simpleName}: ${e.message}), referentie $correlatieId" }
        return ResponseEntity(mapOf("code" to code, "correlatieId" to correlatieId), HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private suspend fun bufferFileContent(file: FilePart): ByteArray {
        val dataBuffer = DataBufferUtils.join(file.content()).awaitSingle()
        return try {
            val bytes = ByteArray(dataBuffer.readableByteCount())
            dataBuffer.read(bytes)
            bytes
        } finally {
            DataBufferUtils.release(dataBuffer)
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}