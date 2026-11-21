/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.catalogiapi.client

import nl.nlportal.catalogiapi.domain.BesluitType
import nl.nlportal.catalogiapi.domain.ResultPage
import nl.nlportal.catalogiapi.domain.ResultaatType
import nl.nlportal.catalogiapi.domain.StatusType
import nl.nlportal.catalogiapi.domain.ZaakStatusType
import nl.nlportal.catalogiapi.domain.ZaakType
import nl.nlportal.core.koppeling.KoppelingClient
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.get
import java.util.UUID

/**
 * De Catalogi API van de huidige tenant. De bron (URL en authenticatie) komt per aanroep uit het [KoppelingRegister] en volgt de
 * tenant uit de context; een aanroep gaat dus nooit naar de bron van een andere tenant. Er wordt niets per tenant bewaard.
 */
class CatalogiApiClient(
    private val register: KoppelingRegister,
) {
    suspend fun getStatusTypes(zaakType: String): List<StatusType> =
        koppeling()
            .get<ResultPage<StatusType>>("$PAD/statustypen", mapOf("zaaktype" to listOf(zaakType), "status" to listOf("definitief")), CRS_HEADERS)
            .results

    suspend fun getStatusType(statusTypeId: UUID): ZaakStatusType = koppeling().get("$PAD/statustypen/$statusTypeId", headers = CRS_HEADERS)

    suspend fun getZaakType(zaakTypeId: UUID): ZaakType = koppeling().get("$PAD/zaaktypen/$zaakTypeId", headers = CRS_HEADERS)

    suspend fun getBesluitTypes(zaakType: String): List<BesluitType> = koppeling().get<ResultPage<BesluitType>>("$PAD/besluittypen", mapOf("zaaktype" to listOf(zaakType)), CRS_HEADERS).results

    suspend fun getBesluitType(besluitTypeId: UUID): BesluitType = koppeling().get("$PAD/besluittypen/$besluitTypeId", headers = CRS_HEADERS)

    suspend fun getResultaatType(resultaatTypeId: UUID): ResultaatType = koppeling().get("$PAD/resultaattypen/$resultaatTypeId", headers = CRS_HEADERS)

    /** De URL van een zaaktype in de Catalogi API van de huidige tenant, voor een filterwaarde (`zaaktype__not_in`) in een andere API. */
    suspend fun zaaktypeUrl(zaakTypeId: UUID): String = koppeling().url("$PAD/zaaktypen/$zaakTypeId")

    // De client wordt per aanroep opgehaald en nooit bewaard: hij hoort bij één tenant.
    private suspend fun koppeling(): KoppelingClient = register.current(MODULE)

    companion object {
        /** Modulenaam = sleutel in `nl-portal.config` en naam van de koppeling in het tenantbestand. */
        const val MODULE = "catalogiapi"
        private const val PAD = "/catalogi/api/v1"

        /** Wat de Catalogi API vraagt voor de coördinaten van geometrie; stond voorheen als standaardheader op de client. */
        private val CRS_HEADERS = mapOf("Accept-Crs" to "EPSG:4326", "Content-Crs" to "EPSG:4326")
    }
}