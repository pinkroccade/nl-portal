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
package nl.nlportal.zakenapi.client.request

import nl.nlportal.core.koppeling.ResultPage
import nl.nlportal.zakenapi.client.ZakenApiClient
import nl.nlportal.zakenapi.client.ZakenApiClient.Companion.ZAKEN_PAD
import nl.nlportal.zakenapi.domain.ZaakSubStatus
import java.util.UUID

class ZaakSubStatussenImpl(
    val zakenApiClient: ZakenApiClient,
) : ZaakSubStatussen {
    override fun search(): SearchZaakSubStatussen = SearchZaakSubStatussenImpl(zakenApiClient)

    override fun get(id: UUID): GetZaakSubStatus = GetZaakSubStatussenImpl(zakenApiClient, id)
}

class GetZaakSubStatussenImpl(
    val zakenApiClient: ZakenApiClient,
    val id: UUID,
) : GetZaakSubStatus {
    override suspend fun retrieve(): ZaakSubStatus = zakenApiClient.haal("$ZAKEN_PAD/substatussen/$id")
}

class SearchZaakSubStatussenImpl(
    val zakenApiClient: ZakenApiClient,
) : SearchZaakSubStatussen {
    private val criteria = Criteria<String>()

    override fun forZaak(zaakUrl: String): SearchZaakSubStatussen {
        criteria.add("zaak", zaakUrl)
        return this
    }

    override fun forStatus(zaakStatusUrl: String): SearchZaakSubStatussen {
        criteria.add("status", zaakStatusUrl)
        return this
    }

    override fun page(page: Int): SearchZaakSubStatussen {
        criteria.add("page", page.toString())
        return this
    }

    override fun pageSize(pageSize: Int): SearchZaakSubStatussen {
        criteria.add("pageSize", pageSize.toString())
        return this
    }

    override suspend fun retrieve(): ResultPage<ZaakSubStatus> = zakenApiClient.haalPagina("$ZAKEN_PAD/substatussen", criteria.bouw())

    override suspend fun retrieveAll(): List<ZaakSubStatus> = zakenApiClient.haalAlles("$ZAKEN_PAD/substatussen", criteria.bouw())
}