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
import nl.nlportal.zakenapi.domain.ZaakObject
import java.util.UUID

class ZaakObjectenImpl(
    val zakenApiClient: ZakenApiClient,
) : ZaakObjecten {
    override fun search(): SearchZaakObjecten = SearchZaakObjectenImpl(zakenApiClient)

    override fun get(id: UUID): GetZaakOject = GetZaakObjectImpl(zakenApiClient, id)
}

class SearchZaakObjectenImpl(
    val zakenApiClient: ZakenApiClient,
) : SearchZaakObjecten {
    private val criteria = Criteria<String>()

    override fun forZaak(zaakUri: String): SearchZaakObjecten {
        criteria.add("zaak", zaakUri)
        return this
    }

    override fun forZaak(id: UUID): SearchZaakObjecten {
        criteria.later { it.add("zaak", zakenApiClient.getZaakUrl(id)) }
        return this
    }

    override fun ofObject(objectUri: String): SearchZaakObjecten {
        criteria.add("object", objectUri)
        return this
    }

    override fun ofObjectType(objectType: ObjectType): SearchZaakObjecten {
        criteria.add("objectType", objectType.value)
        return this
    }

    override fun page(page: Int): SearchZaakObjecten {
        criteria.add("page", page.toString())
        return this
    }

    override fun pageSize(pageSize: Int): SearchZaakObjecten {
        criteria.add("pageSize", pageSize.toString())
        return this
    }

    override suspend fun retrieve(): ResultPage<ZaakObject> = zakenApiClient.haalPagina("$ZAKEN_PAD/zaakobjecten", criteria.bouw())

    override suspend fun retrieveAll(): List<ZaakObject> = zakenApiClient.haalAlles("$ZAKEN_PAD/zaakobjecten", criteria.bouw())
}

class GetZaakObjectImpl(
    val zakenApiClient: ZakenApiClient,
    val id: UUID,
) : GetZaakOject {
    override suspend fun retrieve(): ZaakObject = zakenApiClient.haal("$ZAKEN_PAD/zaakobjecten/$id")
}