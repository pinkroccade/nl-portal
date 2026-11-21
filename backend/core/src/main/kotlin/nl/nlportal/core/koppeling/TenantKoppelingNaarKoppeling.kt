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
 * verwijzing naar een geheim dat in de secret store ontbreekt weigert het opstarten, met de OIN en het veld in de melding
 * (nooit de naam of waarde van het geheim: dat kan een geheim zijn, story 14).
 */
internal fun TenantKoppeling.naarKoppeling(
    oin: Oin,
    naam: String,
    bron: GeheimenBron,
): Koppeling {
    fun geheim(
        veld: String,
        verwijzing: GeheimVerwijzing?,
    ): String? =
        verwijzing?.let {
            try {
                bron.vereis(it)
            } catch (e: GeheimOntbreektException) {
                throw IllegalStateException("Tenant ${oin.value}: koppeling '$naam': auth.$veld: geheim staat niet in de secret store of is leeg", e)
            }
        }
    val bestand = this
    // Het ZGW-JWT is HS256: een korter geheim faalt anders pas bij de eerste aanroep, als storing van de koppeling.
    val zgwSecret = if (auth.type == TenantKoppelingAuthType.ZGW_JWT) geheim("secret", auth.secret) else null
    check(zgwSecret == null || zgwSecret.length >= ZGW_SECRET_MINIMUM) {
        "Tenant ${oin.value}: koppeling '$naam': auth.secret: een ZGW-geheim is minstens $ZGW_SECRET_MINIMUM tekens lang"
    }
    return Koppeling().apply {
        url = bestand.url
        leverancier = bestand.leverancier
        versie = bestand.versie
        auth =
            KoppelingAuth().apply {
                type = bestand.auth.type.naarType()
                headerName = bestand.auth.headerNaam
                value = geheim("waarde", bestand.auth.waarde)
                token = geheim("token", bestand.auth.token)
                clientId = bestand.auth.clientId
                secret = geheim("secret", bestand.auth.secret)
                tokenUrl = bestand.auth.tokenUrl
                privateKeyFile = bestand.auth.privateKeyBestand
            }
    }
}

private const val ZGW_SECRET_MINIMUM = 32

private fun TenantKoppelingAuthType.naarType() =
    when (this) {
        TenantKoppelingAuthType.API_KEY -> KoppelingAuthType.API_KEY
        TenantKoppelingAuthType.BEARER -> KoppelingAuthType.BEARER
        TenantKoppelingAuthType.ZGW_JWT -> KoppelingAuthType.ZGW_JWT
        TenantKoppelingAuthType.OAUTH2_CLIENT_CREDENTIALS -> KoppelingAuthType.OAUTH2_CLIENT_CREDENTIALS
        TenantKoppelingAuthType.OAUTH2_PRIVATE_KEY_JWT -> KoppelingAuthType.OAUTH2_PRIVATE_KEY_JWT
    }