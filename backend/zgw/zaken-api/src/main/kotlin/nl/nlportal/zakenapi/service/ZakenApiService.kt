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
package nl.nlportal.zakenapi.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import nl.nlportal.commonground.authentication.AuthenticationMachtigingsDienstService
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.util.ModuleNotConfiguredException
import nl.nlportal.core.util.ZgwUrls.extractId
import nl.nlportal.documentenapi.domain.Document
import nl.nlportal.documentenapi.service.DocumentenApiService
import nl.nlportal.zakenapi.client.ZakenApiClient
import nl.nlportal.zakenapi.domain.Zaak
import nl.nlportal.zakenapi.domain.ZaakDetails
import nl.nlportal.zakenapi.domain.ZaakDetailsObject
import nl.nlportal.zakenapi.domain.ZaakDocument
import nl.nlportal.zakenapi.domain.ZaakPage
import nl.nlportal.zakenapi.domain.ZaakResultaat
import nl.nlportal.zakenapi.domain.ZaakRol
import nl.nlportal.zakenapi.domain.ZaakStatus
import nl.nlportal.zakenapi.domain.ZaakSubStatus
import nl.nlportal.zakenapi.domain.ZaakSubStatusDoelgroep
import nl.nlportal.zgw.objectenapi.service.ObjectenApiService
import nl.nlportal.zgw.objectenapi.domain.ObjectsApiObject
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class ZakenApiService(
    private val zakenApiClient: ZakenApiClient,
    private val documentenApiService: DocumentenApiService,
    private val objectenApiService: ObjectenApiService,
    private val authenticationMachtigingsDienstService: AuthenticationMachtigingsDienstService,
) {
    suspend fun getZaken(
        page: Int,
        pageSize: Int? = null,
        authentication: PortalAuthentication,
        zaakTypeUrl: String? = null,
        isOpen: Boolean? = null,
        identificatie: String? = null,
        omschrijving: String? = null,
        identificatieContains: String? = null,
        zaakTypeUUIDs: List<UUID> = emptyList(),
    ): ZaakPage {
        val request =
            zakenApiClient
                .zoeken()
                .search()
                .page(page)
                .withAuthentication(authentication)
                .withRolOmschrijvingGeneriek()

        pageSize?.let { request.pageSize(it) }
        zaakTypeUrl?.let { request.ofZaakType(it) }
        isOpen?.let {
            request.isOpen(isOpen)
        }

        identificatie?.let {
            request.ofIdentificatie(identificatie)
        }

        identificatieContains?.let {
            request.ofIdenfitifactieContains(identificatieContains)
        }

        omschrijving?.let {
            request.ofOmschrijving(it)
        }

        authenticationMachtigingsDienstService.zaakTypes(authentication)?.let {
            request.ofZaakTypes(it)
        }

        val uitgesloten = zakenApiClient.configuratie().uitgeslotenZaaktypeIds()
        if (uitgesloten.isNotEmpty()) {
            request.notInZaakTypes(uitgesloten)
        }

        if (zaakTypeUUIDs.isNotEmpty()) {
            request.ofZaakTypes(zaakTypeUUIDs.toList())
        }

        // De bron past zaaktype__not_in toe met zaaktype-URL's uit de Catalogi API; heeft de tenant een eigen Catalogi, dan
        // komen die URL's niet overeen. Daarom filteren we ook op zaaktype-UUID, onafhankelijk van de URL-basis.
        return request.retrieve().let { resultaat ->
            val toegestaan = resultaat.results.filterNot { uitgesloten.isNotEmpty() && isUitgesloten(it.zaaktype, uitgesloten) }
            val verwijderd = resultaat.results.size - toegestaan.size
            ZaakPage.fromResultPage(page, pageSize ?: 20, resultaat.copy(count = (resultaat.count - verwijderd).coerceAtLeast(0), results = toegestaan))
        }
    }

    /** Een zaaktype-URL zonder herkenbare UUID geldt als uitgesloten: bij twijfel niet tonen. */
    private fun isUitgesloten(
        zaaktypeUrl: String,
        uitgesloten: List<UUID>,
    ): Boolean = runCatching { extractId(zaaktypeUrl) }.map { it in uitgesloten }.getOrDefault(true)

    suspend fun getZaak(
        id: UUID,
        authentication: PortalAuthentication,
    ): Zaak {
        // Get rollen of zaak to check if user has access
        val zaakRollenRequest =
            zakenApiClient
                .zaakRollen()
                .search()
                .forZaak(id)
                .withAuthentication(authentication)
                .withOmschrijvingGeneriek()

        val rollen: List<ZaakRol> =
            zaakRollenRequest
                .retrieveAll()

        // if no rol is found, the current user does not have access to this zaak
        if (rollen.isEmpty()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access denied to this zaak")
        }

        val zaak = zakenApiClient.zaken().get(id).retrieve()

        // Een uitgesloten zaaktype komt niet in de lijst; ook een directe aanvraag gedraagt zich alsof de zaak niet bestaat.
        val uitgesloten = zakenApiClient.configuratie().uitgeslotenZaaktypeIds()
        if (uitgesloten.isNotEmpty() && isUitgesloten(zaak.zaaktype, uitgesloten)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

        if (!authenticationMachtigingsDienstService.isAllowedZaakType(authentication, extractId(zaak.zaaktype))) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access denied to this zaak")
        }

        return zaak
    }

    suspend fun getZaakFromZaakApi(id: UUID): Zaak = zakenApiClient.zaken().get(id).retrieve()

    suspend fun getZaakStatus(statusUrl: String): ZaakStatus = zakenApiClient.zaakStatussen().get(extractId(statusUrl)).retrieve()

    suspend fun getZaakSubStatussen(
        zaakUrl: String,
        statusUrl: String,
    ): List<ZaakSubStatus> {
        try {
            return zakenApiClient
                .zaakSubStatussen()
                .search()
                .page(1)
                .forZaak(zaakUrl)
                .forStatus(statusUrl)
                .retrieve()
                .results
                .filterNot { it.doelgroep == ZaakSubStatusDoelgroep.INTERN }
                .sortedBy { it.tijdstip }
        } catch (ex: ModuleNotConfiguredException) {
            // Ontbrekende configuratie of tenant faalt gesloten, net als de andere operaties: geen stille lege lijst.
            throw ex
        } catch (ex: TenantContextMissingException) {
            throw ex
        } catch (ex: Exception) {
            // Geen zaak- of status-URL in de log: een zaak-URL geldt als gevoelig. Bij een koppelingsfout volstaat de referentie.
            val referentie = (ex as? KoppelingException)?.correlatieId
            logger.warn { "Could not get zaak sub statussen: ${ex::class.simpleName}, referentie $referentie" }
        }
        return emptyList()
    }

    suspend fun getDocumenten(zaakUrl: String): List<Document> {
        val documents =
            getZaakDocumenten(zaakUrl)
                .map { zaakDocument ->
                    documentenApiService
                        .getDocument(zaakDocument.informatieobject)
                        .copy(identificatie = zaakDocument.uuid)
                }

        return documentenApiService.filterDocuments(documents)
    }

    suspend fun getZaakStatusHistory(zaakId: UUID): List<ZaakStatus> =
        zakenApiClient
            .zaakStatussen()
            .search()
            .forZaak(zaakId)
            .retrieveAll()

    suspend fun getZaakDocumenten(zaakUrl: String): List<ZaakDocument> =
        zakenApiClient
            .zaakInformatieobjecten()
            .search()
            .forZaak(zaakUrl)
            .retrieve()

    suspend fun getZaakResultaat(resultaatUrl: String): ZaakResultaat = zakenApiClient.zaakResultaten().get(extractId(resultaatUrl)).retrieve()

    suspend fun getZaakDetails(zaakUrl: String): ZaakDetails {
        val zaakId = extractId(zaakUrl)
        val zaakDetailsObjects =
            zakenApiClient
                .zaakObjecten()
                .search()
                .forZaak(zaakId)
                .retrieveAll()
                .filter { it.objectTypeOverige.lowercase(Locale.getDefault()) == "portaalzaakdetails" }
                .map { getObjectApiZaakDetails(it.objectUrl) }
                .flatMap { it?.record?.data?.data!! }
        return ZaakDetails(zaakUrl, zaakDetailsObjects)
    }

    // De URL komt uit een antwoord van de Zaken API en wordt dus niet vertrouwd: alleen via de service, die de host van de Objecten API
    // controleert, nooit rechtstreeks via de client (die stuurt zijn token naar elke URL).
    private suspend fun getObjectApiZaakDetails(objectUrl: String): ObjectsApiObject<ZaakDetailsObject>? =
        objectenApiService.getObjectByUrl<ZaakDetailsObject>(
            url = objectUrl,
        )

    suspend fun getZaakDocumentContent(
        zaakDocumentId: String,
        commonGroundAuthentication: PortalAuthentication,
    ): Pair<Document?, Flow<DataBuffer>?> {
        val zaakDocument =
            zakenApiClient
                .zaakInformatieobjecten()
                .get(UUID.fromString(zaakDocumentId))
                .retrieve()

        val zaakRollen =
            zakenApiClient
                .zaakRollen()
                .search()
                .forZaak(zaakDocument.zaak)
                .withAuthentication(commonGroundAuthentication)
                .retrieveAll()

        return when (zaakRollen.isNotEmpty()) {
            true -> {
                documentenApiService
                    .getDocument(zaakDocument.informatieobject) to
                    documentenApiService
                        .getDocumentContentStreaming(zaakDocument.informatieobject)
            }

            else -> {
                null to null
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}