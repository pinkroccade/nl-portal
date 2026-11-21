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

import nl.nlportal.zakenapi.client.ZakenApiClient
import nl.nlportal.zakenapi.client.ZakenApiClient.Companion.ZAKEN_PAD
import nl.nlportal.zakenapi.domain.ZaakDocument
import java.util.UUID

class ZakenInformatieobjectenImpl(
    val zakenApiClient: ZakenApiClient,
) : ZaakInformatieobjecten {
    override fun search(): SearchZaakInformatieobjecten = SearchZaakInformatieobjectenImpl(zakenApiClient)

    override fun get(id: UUID): GetZaakInformatieobject = GetZaakInformatieobjectImpl(zakenApiClient, id)
}

class GetZaakInformatieobjectImpl(
    val zakenApiClient: ZakenApiClient,
    val id: UUID,
) : GetZaakInformatieobject {
    override suspend fun retrieve(): ZaakDocument = zakenApiClient.haal("$ZAKEN_PAD/zaakinformatieobjecten/$id")
}

class SearchZaakInformatieobjectenImpl(
    val zakenApiClient: ZakenApiClient,
) : SearchZaakInformatieobjecten {
    private val criteria = Criteria<String>()

    override fun forZaak(zaakUri: String): SearchZaakInformatieobjecten {
        criteria.add("zaak", zaakUri)
        return this
    }

    override fun forZaak(id: UUID): SearchZaakInformatieobjecten {
        criteria.later { it.add("zaak", zakenApiClient.getZaakUrl(id)) }
        return this
    }

    override fun ofInformatieobject(informatieobjectUri: String): SearchZaakInformatieobjecten {
        criteria.add("informatieobject", informatieobjectUri)
        return this
    }

    override suspend fun retrieve(): List<ZaakDocument> = zakenApiClient.haal("$ZAKEN_PAD/zaakinformatieobjecten", criteria.bouw())
}