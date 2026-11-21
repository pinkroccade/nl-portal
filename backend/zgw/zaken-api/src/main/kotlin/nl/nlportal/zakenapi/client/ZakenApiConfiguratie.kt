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
package nl.nlportal.zakenapi.client

import nl.nlportal.core.tenancy.ModuleContext
import nl.nlportal.core.tenancy.ModuleOvertreding
import nl.nlportal.core.tenancy.NietErfbaar
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantModuleConfiguratie
import java.util.UUID
import kotlin.reflect.KClass

/**
 * De gegevens van de Zaken API van één tenant, onder `modules.zakenapi` in het tenantbestand. De bron zelf (URL en
 * authenticatie) staat onder `koppelingen.zakenapi`.
 *
 * Beide waarden horen bij de gemeente en komen nooit uit `base.yaml` ([NietErfbaar]).
 *
 * @property uitgeslotenZaaktypen UUID's van zaaktypen die nooit in de zakenlijst komen.
 * @property nnpKvkQueryIdentificatoren zoek een bedrijf als niet-natuurlijk persoon (`kvkNummer`) in plaats van op `annIdentificatie`.
 */
data class ZakenApiConfiguratie(
    @NietErfbaar val uitgeslotenZaaktypen: List<String> = emptyList(),
    @NietErfbaar val nnpKvkQueryIdentificatoren: Boolean = false,
) {
    /** De uitgesloten zaaktypen als UUID; de validatie bij het opstarten garandeert dat het UUID's zijn. */
    fun uitgeslotenZaaktypeIds(): List<UUID> = uitgeslotenZaaktypen.map { UUID.fromString(it) }
}

/** Draagt [ZakenApiConfiguratie] bij aan het tenantbestand; geregistreerd via `META-INF/services`. */
class ZakenApiTenantConfiguratie : TenantModuleConfiguratie<ZakenApiConfiguratie> {
    override val naam = ZakenApiClient.MODULE
    override val klasse: KClass<ZakenApiConfiguratie> = ZakenApiConfiguratie::class

    /** Modulegegevens zonder de koppeling waar ze bij horen, valt bij het laden op en niet pas bij de eerste inwoner. */
    override fun valideer(
        configuratie: ZakenApiConfiguratie,
        context: ModuleContext,
    ): List<ModuleOvertreding> =
        valideer(configuratie) +
            if (ZakenApiClient.MODULE in context.koppelingen) emptyList() else listOf(ModuleOvertreding("", "vereist koppelingen.zakenapi"))

    override fun valideer(configuratie: ZakenApiConfiguratie): List<ModuleOvertreding> =
        configuratie.uitgeslotenZaaktypen.mapIndexedNotNull { index, waarde ->
            if (isUuid(waarde)) null else ModuleOvertreding("uitgeslotenZaaktypen[$index]", "is geen geldige UUID")
        }

    /** `UUID.fromString` accepteert ook korte vormen (`1-1-1-1-1`); een zaaktype-ID is de volledige vorm. */
    private fun isUuid(waarde: String) = runCatching { UUID.fromString(waarde).toString() == waarde.lowercase() }.getOrDefault(false)
}

/**
 * Een tenant met `koppelingen.zakenapi` maar zonder `modules.zakenapi` weigert het opstarten, met de OIN in de melding: zonder
 * modulegegevens faalt `getZaken` pas bij de eerste inwoner. (Het omgekeerde, modulegegevens zonder koppeling, weigert de loader.)
 */
fun controleerKoppelingenHebbenModulegegevens(directory: TenantDirectory) {
    val zonder = directory.tenants.filter { ZakenApiClient.MODULE in it.koppelingen && it.module(ZakenApiConfiguratie::class) == null }
    check(zonder.isEmpty()) {
        "Tenant " + zonder.joinToString { it.oin.value } + ": koppelingen.zakenapi vereist modules.zakenapi (ook `modules.zakenapi: {}` voor de standaardwaarden)"
    }
}