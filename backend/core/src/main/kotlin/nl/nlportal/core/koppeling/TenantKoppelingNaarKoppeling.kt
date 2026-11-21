/*
 * Copyright 2026 PinkRoccade Local Government B.V.
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
package nl.nlportal.core.koppeling

import nl.nlportal.core.tenancy.GeheimOntbreektException
import nl.nlportal.core.tenancy.GeheimVerwijzing
import nl.nlportal.core.tenancy.GeheimenBron
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantKoppeling
import nl.nlportal.core.tenancy.TenantKoppelingAuthType

/**
 * Maakt van de koppeling uit het tenantbestand de [Koppeling] voor het register, met de geheimen opgelost via [bron]. Een
 * verwijzing naar een geheim dat in de secret store ontbreekt weigert het opstarten, met de OIN en de naam van het geheim
 * (nooit een waarde) in de melding (story 14).
 */
internal fun TenantKoppeling.naarKoppeling(
    oin: Oin,
    naam: String,
    bron: GeheimenBron,
): Koppeling {
    fun geheim(verwijzing: GeheimVerwijzing?): String? =
        verwijzing?.let {
            try {
                bron.vereis(it)
            } catch (e: GeheimOntbreektException) {
                throw IllegalStateException("Tenant ${oin.value}: koppeling '$naam': ${e.message}", e)
            }
        }
    val k = this
    return Koppeling().apply {
        url = k.url
        leverancier = k.leverancier
        versie = k.versie
        auth =
            KoppelingAuth().apply {
                type = k.auth.type.naarType()
                headerName = k.auth.headerNaam
                value = geheim(k.auth.waarde)
                token = geheim(k.auth.token)
                clientId = k.auth.clientId
                secret = geheim(k.auth.secret)
                tokenUrl = k.auth.tokenUrl
                privateKeyFile = k.auth.privateKeyBestand
            }
    }
}

private fun TenantKoppelingAuthType.naarType() =
    when (this) {
        TenantKoppelingAuthType.API_KEY -> KoppelingAuthType.API_KEY
        TenantKoppelingAuthType.BEARER -> KoppelingAuthType.BEARER
        TenantKoppelingAuthType.ZGW_JWT -> KoppelingAuthType.ZGW_JWT
        TenantKoppelingAuthType.OAUTH2_CLIENT_CREDENTIALS -> KoppelingAuthType.OAUTH2_CLIENT_CREDENTIALS
        TenantKoppelingAuthType.OAUTH2_PRIVATE_KEY_JWT -> KoppelingAuthType.OAUTH2_PRIVATE_KEY_JWT
    }