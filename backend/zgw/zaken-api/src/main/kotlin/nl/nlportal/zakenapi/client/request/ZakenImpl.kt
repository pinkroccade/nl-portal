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
import nl.nlportal.zakenapi.domain.Zaak
import java.util.UUID

class ZakenImpl(
    val zakenApiClient: ZakenApiClient,
) : Zaken {
    override fun search(): SearchZaken = SearchZakenImpl(zakenApiClient)

    override fun get(id: UUID): GetZaak = GetZaakImpl(zakenApiClient, id)
}

class GetZaakImpl(
    val zakenApiClient: ZakenApiClient,
    val id: UUID,
) : GetZaak {
    override suspend fun retrieve(): Zaak = zakenApiClient.haal("$ZAKEN_PAD/zaken/$id")
}

class SearchZakenImpl(
    val zakenApiClient: ZakenApiClient,
) : SearchZaken {
    private val criteria = Criteria<String>()

    override fun withBsn(bsn: String): SearchZaken {
        criteria.add("rol__betrokkeneIdentificatie__natuurlijkPersoon__inpBsn", bsn)
        return this
    }

    override fun withKvk(kvk: String): SearchZaken {
        criteria.later {
            if (zakenApiClient.useNnpKvkQueryIdentificators()) {
                it.add("rol__betrokkeneIdentificatie__nietNatuurlijkPersoon__kvkNummer", kvk)
            } else {
                it.add("rol__betrokkeneIdentificatie__nietNatuurlijkPersoon__annIdentificatie", kvk)
            }
        }
        return this
    }

    override fun isOpen(open: Boolean): SearchZaken {
        criteria.add("einddatum__isnull", open.toString())
        return this
    }

    override fun ofZaakType(zaakType: String): SearchZaken {
        criteria.add("zaaktype", zaakType)
        return this
    }

    override fun ofZaakTypes(zaakTypeIds: List<UUID>): SearchZaken = throw NotImplementedError("List of zaak types are not supported")

    override fun notInZaakTypes(zaakTypeIds: List<UUID>): SearchZaken = throw NotImplementedError("List of zaak types are not supported")

    override fun ofIdentificatie(identificatie: String): SearchZaken {
        criteria.add("identificatie", identificatie)
        return this
    }

    override fun ofIdenfitifactieContains(identificatie: String): SearchZaken {
        criteria.add("identificatie__icontains", identificatie)
        return this
    }

    override fun ofOmschrijving(omschrijving: String): SearchZaken {
        criteria.add("omschrijving", omschrijving)
        return this
    }

    override fun withKvkAndVestigingsNummer(
        kvkNummer: String,
        vestigingsNummer: String,
    ): SearchZaken {
        criteria.later {
            if (zakenApiClient.useNnpKvkQueryIdentificators()) {
                it.add("rol__betrokkeneIdentificatie__nietNatuurlijkPersoon__vestigingsNummer", vestigingsNummer)
                it.add("rol__betrokkeneIdentificatie__nietNatuurlijkPersoon__kvkNummer", kvkNummer)
            } else {
                it.add("rol__betrokkeneIdentificatie__vestiging__vestigingsNummer", vestigingsNummer)
                it.add("rol__betrokkeneIdentificatie__vestiging__kvkNummer", kvkNummer)
            }
        }
        return this
    }

    override fun page(page: Int): SearchZaken {
        criteria.add("page", page.toString())
        return this
    }

    override fun pageSize(pageSize: Int): SearchZaken {
        criteria.add("pageSize", pageSize.toString())
        return this
    }

    override fun withRolOmschrijvingGeneriek(): SearchZaken {
        criteria.add("rol__omschrijvingGeneriek", "initiator")
        return this
    }

    override suspend fun retrieve(): ResultPage<Zaak> = zakenApiClient.haalPagina("$ZAKEN_PAD/zaken", criteria.bouw())

    override suspend fun retrieveAll(): List<Zaak> = zakenApiClient.haalAlles("$ZAKEN_PAD/zaken", criteria.bouw())
}