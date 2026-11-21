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

class ZoekenImpl(
    val zakenApiClient: ZakenApiClient,
) : Zaken {
    override fun search(): SearchZaken = SearchZoekenImpl(zakenApiClient)

    override fun get(id: UUID): GetZaak = throw NotImplementedError()
}

class SearchZoekenImpl(
    val zakenApiClient: ZakenApiClient,
) : SearchZaken {
    private val criteria = Criteria<Any>()
    private var page: Int? = null
    private var pageSize: Int? = null

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
        criteria.add("einddatum__isnull", open)
        return this
    }

    override fun ofZaakType(zaakType: String): SearchZaken {
        criteria.add("zaaktype", zaakType)
        return this
    }

    override fun ofZaakTypes(zaakTypeIds: List<UUID>): SearchZaken {
        criteria.put("zaaktype__in", zaakTypeIds.map { zakenApiClient.getZaakTypeUrl(it) })
        return this
    }

    override fun notInZaakTypes(zaakTypeIds: List<UUID>): SearchZaken {
        criteria.put("zaaktype__not_in", zaakTypeIds.map { zakenApiClient.getZaakTypeUrl(it) })
        return this
    }

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

    // Moet als queryparameter, niet in de body.
    override fun page(page: Int): SearchZaken {
        this.page = page
        return this
    }

    // Moet als queryparameter, niet in de body.
    override fun pageSize(pageSize: Int): SearchZaken {
        this.pageSize = pageSize
        return this
    }

    override fun withRolOmschrijvingGeneriek(): SearchZaken {
        criteria.add("rol__omschrijvingGeneriek", "initiator")
        return this
    }

    override suspend fun retrieve(): ResultPage<Zaak> = pagina(page)

    /** Een zoekvraag is een POST: de volgende pagina volgt uit het paginanummer, een URL van de bron wordt niet gevolgd. */
    override suspend fun retrieveAll(): List<Zaak> {
        val resultaat = mutableListOf<Zaak>()
        var nummer = page ?: 1
        var pagina = 0
        do {
            check(++pagina <= MAX_PAGINAS) { "Meer dan $MAX_PAGINAS pagina's zaken; afgebroken" }
            val antwoord = pagina(nummer++)
            resultaat += antwoord.results
        } while (antwoord.next != null)
        return resultaat
    }

    private suspend fun pagina(nummer: Int?): ResultPage<Zaak> {
        val query =
            buildMap {
                nummer?.let { put("page", listOf(it.toString())) }
                pageSize?.let { put("pageSize", listOf(it.toString())) }
            }
        return zakenApiClient.zoek("$ZAKEN_PAD/zaken/_zoek", criteria.bouw(), query)
    }

    private companion object {
        const val MAX_PAGINAS = 100
    }
}