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
package nl.nlportal.core.testing

import nl.nlportal.core.tenancy.GeheimVerwijzing
import nl.nlportal.core.tenancy.NietErfbaar
import nl.nlportal.core.tenancy.ProefModule
import nl.nlportal.core.tenancy.TenantModuleConfiguratie
import nl.nlportal.core.tenancy.TenantModules
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

data class VerdachtItem(
    val apiKey: String,
    val naam: String = "n",
)

data class VerdachtConfiguratie(
    val wachtwoord: String,
    val geheimVerwijzing: GeheimVerwijzing,
    val items: List<VerdachtItem> = emptyList(),
    val perType: Map<String, VerdachtItem> = emptyMap(),
    @NietErfbaar val clientSecretBestand: String? = null,
    val keyId: String = "kid",
    val tokenTtl: Int = 5,
    val gewoon: String = "x",
)

class VerdachtModule : TenantModuleConfiguratie<VerdachtConfiguratie> {
    override val naam = "verdacht"
    override val klasse: KClass<VerdachtConfiguratie> = VerdachtConfiguratie::class
}

/** Seam: de controle die de `:app`-test over alle moduleconfiguraties draait (review-bevinding M3). */
class ModuleGeheimenControleTest {
    private val modules = TenantModules.van(listOf(VerdachtModule(), ProefModule()))

    @Test
    fun `een tekstveld met een geheimachtige naam wordt gemeld, ook in lijsten, maps en geneste klassen`() {
        val gemeld = ModuleGeheimenControle.verdachteVelden(modules)

        assertThat(gemeld).containsExactlyInAnyOrder("verdacht.wachtwoord", "verdacht.items.apiKey", "verdacht.perType.apiKey", "verdacht.keyId")
    }

    @Test
    fun `een verwijzing, een niet-erfbaar veld, een getal en een uitzondering worden niet gemeld`() {
        val gemeld = ModuleGeheimenControle.verdachteVelden(modules, uitzonderingen = setOf("keyId", "verdacht.items.apiKey"))

        assertThat(gemeld).containsExactlyInAnyOrder("verdacht.wachtwoord", "verdacht.perType.apiKey")
    }

    @Test
    fun `zonder modules is er niets te melden`() {
        assertThat(ModuleGeheimenControle.verdachteVelden(TenantModules.GEEN)).isEmpty()
    }
}