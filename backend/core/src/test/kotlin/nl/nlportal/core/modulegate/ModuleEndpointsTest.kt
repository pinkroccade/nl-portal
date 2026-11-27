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
package nl.nlportal.core.modulegate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory

class ModuleEndpointsTest {
    private val flag = ModuleFlag("voorbeeld")

    @Test
    fun `a module with a flag bean has a module assignment`() {
        val endpoints = ModuleEndpoints(StaticListableBeanFactory(), listOf(flag))

        assertEquals(EndpointToewijzing.Module(flag), endpoints.moduleToewijzing("voorbeeld"))
    }

    @Test
    fun `a module without a flag bean fails with its name`() {
        val endpoints = ModuleEndpoints(StaticListableBeanFactory(), listOf(flag))

        val fout = assertThrows<IllegalStateException> { endpoints.moduleToewijzing("onbekend") }
        assertEquals("@ModuleVeld noemt module 'onbekend', maar er is geen ModuleFlag-bean met die naam", fout.message)
    }
}