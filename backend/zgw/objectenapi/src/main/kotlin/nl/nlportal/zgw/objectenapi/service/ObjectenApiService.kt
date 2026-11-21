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
package nl.nlportal.zgw.objectenapi.service

import nl.nlportal.core.koppeling.HostBinding
import nl.nlportal.zgw.objectenapi.autoconfiguration.ObjectsApiClientConfig.ObjectsApiClientConfigProperties
import nl.nlportal.zgw.objectenapi.client.ObjectsApiClient
import nl.nlportal.zgw.objectenapi.domain.CreateObjectsApiObjectRequest
import nl.nlportal.zgw.objectenapi.domain.ObjectSearchParameter
import nl.nlportal.zgw.objectenapi.domain.ObjectsApiObject
import nl.nlportal.zgw.objectenapi.domain.ResultPage
import nl.nlportal.zgw.objectenapi.domain.UpdateObjectsApiObjectRequest
import java.net.URI
import java.util.UUID

class ObjectenApiService(
    val objectsApiClient: ObjectsApiClient,
    val objectsApiClientConfig: ObjectsApiClientConfigProperties,
) {
    suspend inline fun <reified T> getObjects(
        objectSearchParameters: List<ObjectSearchParameter>,
        objectTypeUrl: String,
        pageNumber: Int,
        pageSize: Int,
    ): ResultPage<ObjectsApiObject<T>> =
        objectsApiClient.getObjects(
            objectSearchParameters = objectSearchParameters,
            objectTypeUrl = objectTypeUrl,
            page = pageNumber,
            pageSize = pageSize,
        )

    suspend inline fun <reified T> getObjectById(objectId: String): ObjectsApiObject<T>? = objectsApiClient.getObjectById(objectId)

    suspend inline fun <reified T> updateObject(
        objectUuid: UUID,
        objectsApiObject: UpdateObjectsApiObjectRequest<T>,
    ): ObjectsApiObject<T>? = objectsApiClient.updateObject(objectUuid, objectsApiObject)

    suspend inline fun <reified T> getObjectByUrl(url: String): ObjectsApiObject<T>? = objectsApiClient.getObjectByUrl(controleerObjectUrl(url))

    /**
     * Een object-URL komt uit het antwoord van een andere bron en krijgt het token van de Objecten API alleen als hij onder
     * de basis-URL van die API valt: schema, host, poort en padvoorvoegsel (op segmentgrens). Dezelfde regel als de
     * host-binding van de koppelingen. Geeft de gecontroleerde [URI] terug; die gaat naar de client, niet de ruwe tekst.
     */
    @PublishedApi
    internal fun controleerObjectUrl(url: String): URI =
        try {
            HostBinding.valtOnder(objectsApiClientConfig.url, url)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("De object-URL valt buiten de geconfigureerde Objecten API: ${e.message}", e)
        }

    suspend inline fun <reified T> createObject(createObjectsApiObjectRequest: CreateObjectsApiObjectRequest<T>): ObjectsApiObject<T> =
        objectsApiClient.createObject(
            createObjectsApiObjectRequest,
        )

    suspend fun deleteObjectById(objectId: UUID) {
        objectsApiClient.deleteObjectById(objectId)
    }
}