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

import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.Flag
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import nl.nlportal.core.featureflag.FeatureContextFactory
import nl.nlportal.core.featureflag.FeatureFlagProperties
import nl.nlportal.core.featureflag.OpenFeatureFeatureGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ModuleGateTest {
    private val berichten = ModuleFlag("berichten")
    private val taak = ModuleFlag("taak")
    private val documenten = ModuleFlag("documenten")
    private val objecten = ModuleFlag("objecten")
    private val dependent = ModuleFlag("afhankelijk", requires = listOf(documenten, objecten))
    private val chained = ModuleFlag("keten", requires = listOf(dependent))

    private fun gateWith(
        flags: Map<String, Boolean>,
        modules: List<ModuleFlag> = listOf(berichten, taak),
    ): ModuleGate {
        val api = OpenFeatureAPI.getInstance()
        api.setProviderAndWait(
            InMemoryProvider(
                flags.mapValues { (_, enabled) ->
                    Flag
                        .builder<Boolean>()
                        .variant("v", enabled)
                        .defaultVariant("v")
                        .build()
                },
            ),
        )
        return ModuleGate(
            featureGate = OpenFeatureFeatureGate(api.client),
            featureContextFactory = FeatureContextFactory(FeatureFlagProperties.Context()),
            modules = modules,
        )
    }

    @Test
    fun `a module flag is an ops flag named after the module`() {
        assertEquals("ops.berichten.enabled", berichten.flag.key)
    }

    @Test
    fun `a module is enabled when its flag is on`() {
        val gate = gateWith(mapOf("ops.berichten.enabled" to true))

        assertTrue(gate.isEnabled(berichten, ImmutableContext()))
        assertDoesNotThrow { gate.require(berichten, null) }
    }

    @Test
    fun `a module is disabled when its flag is off or unknown`() {
        val gate = gateWith(mapOf("ops.berichten.enabled" to false))

        assertFalse(gate.isEnabled(berichten, ImmutableContext()))
        assertFalse(gate.isEnabled(taak, ImmutableContext()))
    }

    @Test
    fun `requiring a disabled module throws a not-available error naming the module`() {
        val gate = gateWith(mapOf("ops.berichten.enabled" to false))

        val error = assertThrows<ModuleNotAvailableException> { gate.require(berichten, null) }

        assertEquals("berichten", error.module)
        assertEquals("Module 'berichten' is niet beschikbaar", error.message)
    }

    @Test
    fun `the status lists every registered module with its flag outcome`() {
        val gate = gateWith(mapOf("ops.berichten.enabled" to true, "ops.taak.enabled" to false))

        assertEquals(mapOf("berichten" to true, "taak" to false), gate.status(ImmutableContext()))
    }

    @Test
    fun `a module is enabled when its own flag and all required flags are on`() {
        val gate = gateWith(mapOf("ops.afhankelijk.enabled" to true, "ops.documenten.enabled" to true, "ops.objecten.enabled" to true))

        assertTrue(gate.isEnabled(dependent, ImmutableContext()))
        assertDoesNotThrow { gate.require(dependent, null) }
    }

    @Test
    fun `a module is disabled when one of its required flags is off or unknown`() {
        val offGate = gateWith(mapOf("ops.afhankelijk.enabled" to true, "ops.documenten.enabled" to true, "ops.objecten.enabled" to false))
        val unknownGate = gateWith(mapOf("ops.afhankelijk.enabled" to true, "ops.documenten.enabled" to true))

        assertFalse(offGate.isEnabled(dependent, ImmutableContext()))
        assertFalse(unknownGate.isEnabled(dependent, ImmutableContext()))
        assertThrows<ModuleNotAvailableException> { offGate.require(dependent, null) }
    }

    @Test
    fun `a required flag that is on does not enable a module whose own flag is off`() {
        val gate = gateWith(mapOf("ops.afhankelijk.enabled" to false, "ops.documenten.enabled" to true, "ops.objecten.enabled" to true))

        assertFalse(gate.isEnabled(dependent, ImmutableContext()))
    }

    @Test
    fun `a requirement counts through the whole chain`() {
        val gate =
            gateWith(
                mapOf(
                    "ops.keten.enabled" to true,
                    "ops.afhankelijk.enabled" to true,
                    "ops.documenten.enabled" to true,
                    "ops.objecten.enabled" to false,
                ),
            )

        assertFalse(gate.isEnabled(chained, ImmutableContext()))
    }

    @Test
    fun `the status shows a module with an unmet requirement as off`() {
        val gate = gateWith(mapOf("ops.afhankelijk.enabled" to true, "ops.documenten.enabled" to false), listOf(dependent, documenten))

        assertEquals(mapOf("afhankelijk" to false, "documenten" to false), gate.status(ImmutableContext()))
    }

    @Test
    fun `the dependency graph lists the required modules of every registered module, transitively`() {
        val gate = gateWith(emptyMap(), listOf(chained, taak))

        assertEquals(
            mapOf("keten" to listOf("afhankelijk", "documenten", "objecten"), "taak" to emptyList()),
            gate.dependencies(),
        )
    }
}