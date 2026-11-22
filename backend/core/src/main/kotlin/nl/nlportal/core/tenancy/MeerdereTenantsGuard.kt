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
package nl.nlportal.core.tenancy

import org.springframework.beans.factory.InitializingBean

/**
 * Een module die nog haar eigen client bouwt en dus niet per tenant werkt (de pakketten in `HttpClientRules.NOT_YET_MIGRATED`).
 * Zo'n module registreert een bean van dit type in haar autoconfiguratie, binnen de voorwaarde die haar aanzet; de bean verdwijnt
 * met de migratie (tegelijk met het pakket in die lijst). Staat de module aan, dan weigert [MeerdereTenantsGuard] een runtime met
 * meer dan één tenant.
 */
data class NietGemigreerdeModule(
    val module: String,
)

/**
 * Weigert het opstarten als een [NietGemigreerdeModule] aan staat in een runtime met meer dan één tenant: zo'n module leest één
 * globale configuratie en zou gegevens van de ene tenant naar de bron van een andere sturen. Het bericht noemt de modulenamen en
 * de OIN's van de tenants, nooit een geheim of een waarde uit de configuratie. Een eigen bean, los van de factory van
 * `koppelingRegister`.
 */
class MeerdereTenantsGuard(
    private val directory: TenantDirectory,
    private val modules: List<NietGemigreerdeModule>,
) : InitializingBean {
    override fun afterPropertiesSet() {
        controleer()?.let { error(it) }
    }

    /** De foutmelding, of `null` als alles in orde is. */
    fun controleer(): String? {
        val namen = modules.map { it.module }.distinct().sorted()
        if (namen.isEmpty() || directory.tenants.size <= 1) return null
        val oins = directory.tenants.map { it.oin.value }.sorted()
        return "De module(s) ${namen.joinToString { "'$it'" }} zijn nog niet gemigreerd naar tenantconfiguratie en kunnen niet aan staan in een runtime " +
            "met meer dan één tenant (OIN: ${oins.joinToString()}). Zet de module uit of draai één tenant."
    }
}