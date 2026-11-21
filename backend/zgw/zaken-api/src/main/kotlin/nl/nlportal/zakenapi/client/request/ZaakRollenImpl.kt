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
import nl.nlportal.zakenapi.domain.ZaakRol
import java.util.UUID

class ZaakRollenImpl(
    val zakenApiClient: ZakenApiClient,
) : ZaakRollen {
    override fun search(): SearchZaakRollen = SearchRollenImpl(zakenApiClient)

    override fun get(id: UUID): GetZaakRol = GetRolImpl(zakenApiClient, id)
}

class GetRolImpl(
    val zakenApiClient: ZakenApiClient,
    val id: UUID,
) : GetZaakRol {
    override suspend fun retrieve(): ZaakRol = zakenApiClient.haal("$ZAKEN_PAD/rollen/$id")
}

class SearchRollenImpl(
    val zakenApiClient: ZakenApiClient,
) : SearchZaakRollen {
    private val criteria = Criteria<String>()

    override fun withBsn(bsn: String): SearchZaakRollen {
        criteria.add("betrokkeneIdentificatie__natuurlijkPersoon__inpBsn", bsn)
        return this
    }

    override fun withKvk(kvk: String): SearchZaakRollen {
        criteria.add("betrokkeneIdentificatie__nietNatuurlijkPersoon__annIdentificatie", kvk)
        return this
    }

    override fun withKvkAndVestigingsNummer(
        kvkNummer: String,
        vestigingsNummer: String,
    ): SearchZaakRollen {
        criteria.add("betrokkeneIdentificatie__vestiging__vestigingsNummer", vestigingsNummer)
        criteria.add("betrokkeneIdentificatie__vestiging__kvkNummer", kvkNummer)
        return this
    }

    override fun ofVestigingsNummer(vestigingsNummer: String): SearchZaakRollen {
        criteria.add("betrokkeneIdentificatie__vestiging__vestigingsNummer", vestigingsNummer)
        return this
    }

    override fun forZaak(zaakUrl: String): SearchZaakRollen {
        criteria.add("zaak", zaakUrl)
        return this
    }

    override fun forZaak(zaakId: UUID): SearchZaakRollen {
        criteria.later { it.add("zaak", zakenApiClient.getZaakUrl(zaakId)) }
        return this
    }

    override fun page(page: Int): SearchZaakRollen {
        criteria.add("page", page.toString())
        return this
    }

    override fun pageSize(pageSize: Int): SearchZaakRollen {
        criteria.add("pageSize", pageSize.toString())
        return this
    }

    override fun withOmschrijvingGeneriek(): SearchZaakRollen {
        criteria.add("omschrijvingGeneriek", "initiator")
        return this
    }

    override suspend fun retrieve(): ResultPage<ZaakRol> = zakenApiClient.haalPagina("$ZAKEN_PAD/rollen", criteria.bouw())

    override suspend fun retrieveAll(): List<ZaakRol> = zakenApiClient.haalAlles("$ZAKEN_PAD/rollen", criteria.bouw())
}