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
package nl.nlportal.commonground.authentication

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import nl.nlportal.core.util.Mapper
import org.springframework.core.io.ResourceLoader
import tools.jackson.core.type.TypeReference

class AuthenticationMachtigingsDienstService(
    val authenticationMachtingsDienstConfig: AuthenticationMachtigingsDienstConfig,
    resourceLoader: ResourceLoader,
) {
    var authenticationMachtingsDiensten: MutableList<AuthenticationMachtigingsDienst> = mutableListOf()

    init {
        try {
            // first load the new machtigingsdienst configurations from the configurations properties
            authenticationMachtingsDiensten.addAll(authenticationMachtingsDienstConfig.getMachtigingsDienstListFromConfiguration())

            // second load the machtigingsdienst from a resource url, to make it backwards compatible
            if (authenticationMachtingsDienstConfig.resourceUrl != null) {
                val json = resourceLoader.getResource(authenticationMachtingsDienstConfig.resourceUrl!!).getContentAsString(Charsets.UTF_8)
                logger.debug { "Machtigingsdiensten is loaded: $json" }
                this.authenticationMachtingsDiensten.addAll(Mapper.get().readValue(json, object : TypeReference<List<AuthenticationMachtigingsDienst>>() {}))
            }
        } catch (ex: Exception) {
            logger.warn { "Could not load json from ${authenticationMachtingsDienstConfig.resourceUrl} with reason ${ex.message}" }
        }
    }

    fun getAuthenticationMachtingsDienst(uuid: UUID?): AuthenticationMachtigingsDienst? {
        if (uuid == null) return null
        return authenticationMachtingsDiensten.find { it.uuid == uuid }
    }

    fun hasMachtingsDienst(authentication: PortalAuthentication): Boolean = machtigingsDienstUuids(authentication) != null

    fun zaakTypes(authentication: PortalAuthentication): List<UUID>? {
        if (!authentication.isBedrijf()) {
            return null
        }
        val zaakTypeList = mutableListOf<UUID>()
        machtigingsDienstUuids(authentication)?.forEach {
            getAuthenticationMachtingsDienst(it)?.let {
                zaakTypeList.addAll(it.zaakTypes)
            }
        }

        return when {
            zaakTypeList.isEmpty() -> {
                null
            }

            else -> {
                zaakTypeList
            }
        }
    }

    fun taakTypes(authentication: PortalAuthentication): List<String>? {
        if (!authentication.isBedrijf()) {
            return null
        }
        val taakTypeList = mutableListOf<String>()
        machtigingsDienstUuids(authentication)?.forEach {
            getAuthenticationMachtingsDienst(it)?.let {
                taakTypeList.addAll(it.taakTypes)
            }
        }
        return when {
            taakTypeList.isEmpty() -> {
                null
            }

            else -> {
                taakTypeList
            }
        }
    }

    fun productTypes(authentication: PortalAuthentication): List<UUID>? {
        if (!authentication.isBedrijf()) {
            return null
        }
        val productTypeList = mutableListOf<UUID>()
        machtigingsDienstUuids(authentication)?.forEach {
            getAuthenticationMachtingsDienst(it)?.let {
                productTypeList.addAll(it.productTypes)
            }
        }
        return when {
            productTypeList.isEmpty() -> {
                null
            }

            else -> {
                productTypeList
            }
        }
    }

    fun isAllowedZaakType(
        authentication: PortalAuthentication,
        zaakTypeUUID: UUID,
    ): Boolean {
        val zaaktypes = zaakTypes(authentication)
        if (!authentication.isBedrijf() || zaaktypes.isNullOrEmpty()) {
            return true
        }
        return zaaktypes.contains(zaakTypeUUID)
    }

    fun isAllowedZaakTypes(
        authentication: PortalAuthentication,
        zaakTypeUUIDs: List<UUID>,
    ): Boolean {
        if (!authentication.isBedrijf()) {
            return true
        }
        val zaaktypes = zaakTypes(authentication)

        val allowedZaaktypes = mutableSetOf<UUID>()

        if (!zaaktypes.isNullOrEmpty()) {
            zaakTypeUUIDs.forEach {
                if (zaaktypes.contains(it)) {
                    allowedZaaktypes.add(it)
                }
            }

            return allowedZaaktypes.isNotEmpty()
        }

        return true
    }

    fun isAllowedTaakType(
        authentication: PortalAuthentication,
        taakType: String,
    ): Boolean {
        val taaktypes = taakTypes(authentication)
        if (!authentication.isBedrijf() || taaktypes.isNullOrEmpty()) {
            return true
        }

        return taaktypes.contains(taakType)
    }

    fun isAllowedProductType(
        authentication: PortalAuthentication,
        productTypeUUID: UUID,
    ): Boolean {
        val productTypes = productTypes(authentication)
        if (!authentication.isBedrijf() || productTypes.isNullOrEmpty()) {
            return true
        }

        return productTypes.contains(productTypeUUID)
    }

    fun isAllowedProductTypes(
        authentication: PortalAuthentication,
        productTypeUUIDs: List<UUID>? = emptyList(),
    ): Boolean =
        filterAllowedProductTypes(
            authentication = authentication,
            productTypeUUIDs = productTypeUUIDs,
        ).isNotEmpty()

    fun filterAllowedProductTypes(
        authentication: PortalAuthentication,
        productTypeUUIDs: List<UUID>?,
    ): List<UUID> {
        val productTypes = productTypes(authentication)
        if (!authentication.isBedrijf() || productTypes.isNullOrEmpty()) {
            return productTypeUUIDs ?: emptyList()
        }

        if (productTypeUUIDs.isNullOrEmpty()) {
            return productTypes
        }

        return productTypeUUIDs.filter { it in productTypes }
    }

    /** De machtigingsdienst-UUID's van de gebruiker; `null` zonder claim of bij de UUID die alle machtigingen geeft. */
    private fun machtigingsDienstUuids(authentication: PortalAuthentication): List<UUID>? =
        authentication.identiteit.machtigingsdienstUuids
            ?.takeUnless { authenticationMachtingsDienstConfig.allMachtigingUuid in it }

    private fun PortalAuthentication.isBedrijf() = identiteit.vertegenwoordigde is Vertegenwoordigde.Bedrijf

    companion object {
        private val logger: KLogger = KotlinLogging.logger {}
    }
}