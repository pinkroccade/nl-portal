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
package nl.nlportal.verificatie.autoconfigure

import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.verificatie.tenant.VerificatieConfiguratie
import nl.nlportal.verificatie.tenant.VerificatieTenantConfiguratie
import org.springframework.beans.factory.InitializingBean

/**
 * Weigert het opstarten als de verificatiemodule aan staat en geen enkele tenant `modules.verificatie` heeft, en als een tenant maar de helft
 * heeft: `koppelingen.verificatie` zonder `modules.verificatie` (of omgekeerd) zou pas bij het eerste verzoek falen.
 */
class VerificatieModuleControle(
    private val directory: TenantDirectory,
) : InitializingBean {
    override fun afterPropertiesSet() {
        directory.tenants.forEach { tenant ->
            val metKoppeling = VerificatieTenantConfiguratie.KOPPELING in tenant.koppelingen
            val metModule = tenant.module(VerificatieConfiguratie::class) != null
            check(metKoppeling == metModule) {
                "Tenant ${tenant.oin.value}: koppelingen.${VerificatieTenantConfiguratie.KOPPELING} en modules.${VerificatieTenantConfiguratie.MODULE} " +
                    "horen samen: er staat alleen ${if (metKoppeling) "de koppeling" else "de module"} in het tenantbestand."
            }
        }
        check(directory.tenants.any { it.module(VerificatieConfiguratie::class) != null }) {
            "De verificatiemodule staat aan (nl-portal.config.verificatie.enabled), maar geen enkele tenant heeft " +
                "modules.${VerificatieTenantConfiguratie.MODULE} in het tenantbestand. Voeg die samen met koppelingen.${VerificatieTenantConfiguratie.KOPPELING} " +
                "toe of zet de verificatiemodule uit."
        }
    }
}