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
package nl.nlportal.besluiten

import nl.nlportal.besluiten.autoconfigure.BesluitenAutoConfiguration
import nl.nlportal.besluiten.graphql.BesluitenQuery
import nl.nlportal.core.modulegate.ModuleEndpoint
import nl.nlportal.documentenapi.DOCUMENTENAPIS_MODULE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class BesluitenModuleTest {
    @Test
    fun `the besluiten flag requires documentenapis and needs no tenant configuration yet`() {
        assertEquals("permission.besluiten.enabled", BESLUITEN_MODULE.flag.key)
        assertEquals(listOf(DOCUMENTENAPIS_MODULE), BESLUITEN_MODULE.requires)
        assertEquals(emptySet<String>(), BESLUITEN_MODULE.koppelingen)
        assertEquals(null, BESLUITEN_MODULE.moduleConfiguratie)
    }

    @Test
    fun `the autoconfiguration registers the flag as a bean`() {
        assertEquals(BESLUITEN_MODULE, BesluitenAutoConfiguration().besluitenModuleFlag())
    }

    @Test
    fun `the besluiten controller is registered at the gate without transition`() {
        val query = BesluitenQuery(besluitenService = mock(), catalogiApiService = mock())
        val registratie = query::class.java.getAnnotation(ModuleEndpoint::class.java)

        assertEquals("besluiten", registratie.module)
        assertFalse(registratie.overgang)
    }
}