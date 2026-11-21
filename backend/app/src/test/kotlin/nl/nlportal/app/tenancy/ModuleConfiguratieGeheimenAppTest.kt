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
package nl.nlportal.app.tenancy

import nl.nlportal.core.tenancy.TenantModules
import nl.nlportal.core.testing.ModuleGeheimenControle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Seam: de moduleconfiguraties van alle modules op het klassenpad van de app. Een geheim is altijd een `GeheimVerwijzing`; een tekstveld
 * met een geheimachtige naam is een `@NietErfbaar` credential (geen geheim, bijvoorbeeld een sleutelpad) of staat hieronder met reden.
 */
class ModuleConfiguratieGeheimenAppTest {
    /** Eigenschapnamen of volledige paden (`<module>.<veld>`) die op een geheim lijken maar er geen zijn; elk met een reden. */
    private val uitzonderingen = emptySet<String>()

    @Test
    fun `geen moduleconfiguratie bevat een geheim als gewone tekst`() {
        val verdacht = ModuleGeheimenControle.verdachteVelden(TenantModules.ontdek(), uitzonderingen)

        assertThat(verdacht)
            .describedAs("Maak er een GeheimVerwijzing van, of markeer een credential dat geen geheim is met @NietErfbaar, of voeg een uitzondering toe met reden")
            .isEmpty()
    }
}