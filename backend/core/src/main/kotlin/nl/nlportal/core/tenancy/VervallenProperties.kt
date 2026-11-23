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

import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.ConfigurationPropertyState
import org.springframework.core.env.Environment

/**
 * Meldt dat de oude properties van een gemigreerde module zijn vervallen: staat er onder [prefix] nog iets (als property, in een
 * yml of als omgevingsvariabele, in elke schrijfwijze) dan weigert het opstarten, zodat een beheerder niet denkt dat een oude
 * doelbinding of sleutel nog geldt. De module registreert een bean van dit type met haar oude prefix, bijvoorbeeld
 * `VervallenProperties("brp", "nl-portal.config.haalcentraal-brp.properties")`; core controleert alle beans bij het opstarten.
 * [vervanging] zegt waar de configuratie nu staat (standaard `modules.<module>` en de koppelingen); een module zonder eigen
 * instellingen per gemeente geeft hier alleen haar koppeling. De melding noemt de prefix, [module] en [vervanging], nooit een waarde.
 */
data class VervallenProperties(
    val module: String,
    val prefix: String,
    val vervanging: String = "modules.$module en koppelingen",
) {
    fun controleer(environment: Environment) {
        check(!isGezet(environment)) {
            "De oude instellingen onder '$prefix' (ook als omgevingsvariabele) zijn vervallen: module '$module' leest haar configuratie uit " +
                "het tenantbestand ($vervanging). Verwijder de oude instellingen."
        }
    }

    private fun isGezet(environment: Environment): Boolean {
        val naam = ConfigurationPropertyName.of(prefix)
        val bronnen = ConfigurationPropertySources.get(environment)
        return bronnen.any { it.containsDescendantOf(naam) == ConfigurationPropertyState.PRESENT }
    }
}