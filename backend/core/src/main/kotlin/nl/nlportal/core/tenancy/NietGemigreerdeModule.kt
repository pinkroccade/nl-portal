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

/**
 * Meldt dat een module aan staat die nog niet naar de tenantconfiguratie en de `KoppelingClient` is gemigreerd (epic #499, T15 tot en
 * met T17: besluiten, openklant, openproduct). Zo'n module leest nog gedeelde instellingen en gebruikt een eigen client: in een runtime
 * met meer dan één tenant zouden de gegevens van de ene gemeente naar de bron van de andere kunnen gaan. De module registreert een bean
 * van dit type in haar autoconfiguratie (die alleen bestaat als de module aan staat); [controleer] weigert dan het opstarten bij
 * meer dan één tenant. De melding noemt alleen modulenamen, nooit een waarde.
 */
data class NietGemigreerdeModule(
    val module: String,
) {
    companion object {
        /** Weigert het opstarten als er meer dan één tenant is en minstens één niet-gemigreerde module aan staat. */
        fun controleer(
            aantalTenants: Int,
            modules: List<NietGemigreerdeModule>,
        ) {
            check(aantalTenants <= 1 || modules.isEmpty()) {
                "De modules ${modules.map { it.module }.sorted().joinToString { "'$it'" }} zijn nog niet naar de tenantconfiguratie gemigreerd en kunnen niet aan staan " +
                    "in een runtime met meer dan één tenant ($aantalTenants tenants). Zet ze uit of laat ze eerst migreren."
            }
        }
    }
}