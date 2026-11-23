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
package nl.nlportal.catalogiapi.autoconfigure

import nl.nlportal.catalogiapi.client.CatalogiApiClient
import nl.nlportal.core.tenancy.TenantDirectory
import org.springframework.beans.factory.InitializingBean

/**
 * Weigert het opstarten als de Catalogi API-module aan staat en geen enkele tenant de koppeling `catalogiapi` heeft: elke aanroep zou
 * dan pas bij het eerste verzoek falen. De module heeft per gemeente alleen een koppeling (geen `modules.catalogiapi`), dus de koppeling
 * is hier de moduleconfiguratie.
 */
class CatalogiApiKoppelingControle(
    private val directory: TenantDirectory,
) : InitializingBean {
    override fun afterPropertiesSet() {
        check(directory.tenants.any { CatalogiApiClient.MODULE in it.koppelingen }) {
            "De Catalogi API-module staat aan (nl-portal.config.catalogiapi.enabled), maar geen enkele tenant heeft de koppeling " +
                "'${CatalogiApiClient.MODULE}' in het tenantbestand (koppelingen.${CatalogiApiClient.MODULE}). Voeg die toe of zet de module uit."
        }
    }
}