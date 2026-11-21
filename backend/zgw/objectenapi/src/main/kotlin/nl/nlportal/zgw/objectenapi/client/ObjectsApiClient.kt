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
package nl.nlportal.zgw.objectenapi.client

import nl.nlportal.core.koppeling.KoppelingClient
import nl.nlportal.core.koppeling.KoppelingException
import nl.nlportal.core.koppeling.KoppelingFoutsoort
import nl.nlportal.core.koppeling.KoppelingRegister
import nl.nlportal.core.koppeling.typeReferentie
import nl.nlportal.zgw.objectenapi.domain.CreateObjectsApiObjectRequest
import nl.nlportal.zgw.objectenapi.domain.ObjectSearchParameter
import nl.nlportal.zgw.objectenapi.domain.ObjectsApiObject
import nl.nlportal.zgw.objectenapi.domain.ResultPage
import nl.nlportal.zgw.objectenapi.domain.UpdateObjectsApiObjectRequest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * De Objecten API van de huidige tenant, via zijn koppeling `objectenapi` (per aanroep opgehaald, nooit bewaard). Een object-URL
 * uit een antwoord van een andere bron volgt alleen als hij onder de basis-URL van die koppeling valt (host-binding).
 */
open class ObjectsApiClient(
    private val koppelingen: KoppelingRegister,
) {
    @PublishedApi
    internal suspend fun koppeling(): KoppelingClient = koppelingen.current(MODULE)

    /** Alleen een 404 van de bron komt als [ResponseStatusException] (NOT_FOUND) naar boven; elke andere fout, ook een 401, 403 of 429, is een [KoppelingException]: een bronstatus zegt iets over onze koppeling, niet over de aanvraag van de inwoner. */
    suspend inline fun <reified T> getObjectById(id: String): ObjectsApiObject<T> =
        try {
            koppeling().get(
                "$OBJECTEN_PAD/${UriUtils.encodePathSegment(id, StandardCharsets.UTF_8)}",
                typeReferentie<ObjectsApiObject<T>>(),
                headers = CRS_HEADERS,
            )
        } catch (e: KoppelingException) {
            throw bronStatus(e, T::class.java.simpleName)
        }

    suspend inline fun <reified T> getObjectByUrl(url: String): ObjectsApiObject<T> = koppeling().get(url, typeReferentie<ObjectsApiObject<T>>(), headers = CRS_HEADERS)

    suspend inline fun <reified T> getObjects(
        objectSearchParameters: List<ObjectSearchParameter>,
        objectTypeUrl: String? = null,
        page: Int,
        pageSize: Int,
        ordering: String? = null,
    ): ResultPage<ObjectsApiObject<T>> {
        val query = mutableMapOf("page" to listOf(page.toString()), "pageSize" to listOf(pageSize.toString()))
        objectTypeUrl?.let { query["type"] = listOf(it) }
        query["data_attrs"] = listOf(ObjectSearchParameter.toQueryParameter(objectSearchParameters))
        ordering?.let { query["ordering"] = listOf(it) }
        return koppeling().get(OBJECTEN_PAD, typeReferentie<ResultPage<ObjectsApiObject<T>>>(), query, CRS_HEADERS)
    }

    suspend inline fun <reified T> updateObject(
        objectUuid: UUID,
        objectsApiObject: UpdateObjectsApiObjectRequest<T>,
    ): ObjectsApiObject<T> = koppeling().put("$OBJECTEN_PAD/$objectUuid", objectsApiObject, typeReferentie<ObjectsApiObject<T>>(), CRS_HEADERS)

    suspend inline fun <reified T> createObject(objectsApiObject: CreateObjectsApiObjectRequest<T>): ObjectsApiObject<T> = koppeling().post(OBJECTEN_PAD, objectsApiObject, typeReferentie<ObjectsApiObject<T>>(), CRS_HEADERS)

    suspend fun deleteObjectById(id: UUID) {
        koppeling().delete("$OBJECTEN_PAD/$id", CRS_HEADERS)
    }

    @PublishedApi
    internal fun bronStatus(
        fout: KoppelingException,
        type: String,
    ): Throwable =
        if (fout.status == HTTP_NOT_FOUND && (fout.soort == KoppelingFoutsoort.BRON_PROBLEEM || fout.soort == KoppelingFoutsoort.BRON_NIET_CONFORM)) {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Failed to GET $type")
        } else {
            fout
        }

    companion object {
        const val MODULE = "objectenapi"

        private const val HTTP_NOT_FOUND = 404

        @PublishedApi
        internal const val OBJECTEN_PAD = "/api/v2/objects"

        @PublishedApi
        internal val CRS_HEADERS = mapOf("Accept-Crs" to "EPSG:4326", "Content-Crs" to "EPSG:4326")
    }
}