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
import nl.nlportal.zakenapi.domain.ZaakStatus
import java.util.UUID

class ZaakStatussenImpl(
    val zakenApiClient: ZakenApiClient,
) : ZaakStatussen {
    override fun search(): SearchZaakStatussen = SearchZaakStatussenImpl(zakenApiClient)

    override fun get(id: UUID): GetZaakStatus = GetZaakStatussenImpl(zakenApiClient, id)
}

class GetZaakStatussenImpl(
    val zakenApiClient: ZakenApiClient,
    val id: UUID,
) : GetZaakStatus {
    override suspend fun retrieve(): ZaakStatus = zakenApiClient.haal("$ZAKEN_PAD/statussen/$id")
}

class SearchZaakStatussenImpl(
    val zakenApiClient: ZakenApiClient,
) : SearchZaakStatussen {
    private val criteria = Criteria<String>()

    override fun forZaak(zaakUrl: String): SearchZaakStatussen {
        criteria.add("zaak", zaakUrl)
        return this
    }

    override fun forZaak(zaakId: UUID): SearchZaakStatussen {
        criteria.later { it.add("zaak", zakenApiClient.getZaakUrl(zaakId)) }
        return this
    }

    override fun forStatustype(statustype: String): SearchZaakStatussen {
        criteria.add("statustype", statustype)
        return this
    }

    override fun page(page: Int): SearchZaakStatussen {
        criteria.add("page", page.toString())
        return this
    }

    override fun pageSize(pageSize: Int): SearchZaakStatussen {
        criteria.add("pageSize", pageSize.toString())
        return this
    }

    override suspend fun retrieve(): ResultPage<ZaakStatus> = zakenApiClient.haalPagina("$ZAKEN_PAD/statussen", criteria.bouw())

    override suspend fun retrieveAll(): List<ZaakStatus> = zakenApiClient.haalAlles("$ZAKEN_PAD/statussen", criteria.bouw())
}