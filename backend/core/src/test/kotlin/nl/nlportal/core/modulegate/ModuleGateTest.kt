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

import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.Flag
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import nl.nlportal.core.featureflag.FeatureContextFactory
import nl.nlportal.core.featureflag.FeatureFlagProperties
import nl.nlportal.core.featureflag.OpenFeatureFeatureGate
import nl.nlportal.core.featureflag.ToggleType
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.testing.TestTenants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ModuleGateTest {
    private val a = TestTenants.BOSRAND
    private val b = TestTenants.WIJDE_VELD

    private val berichten = ModuleFlag("berichten", koppelingen = setOf("objectenapi"), moduleConfiguratie = "berichten")
    private val taak = ModuleFlag("taak")
    private val documenten = ModuleFlag("documenten", moduleConfiguratie = "documenten-api")
    private val objecten = ModuleFlag("objecten", koppelingen = setOf("objectenapi"))
    private val dependent = ModuleFlag("afhankelijk", requires = listOf(documenten, objecten))
    private val chained = ModuleFlag("keten", requires = listOf(dependent))

    private fun tenant(
        oin: Oin,
        vararg koppelingen: String,
        modules: Set<String> = emptySet(),
    ): TenantDefinition =
        TestTenants.tenant(
            oin = oin,
            koppelingen = koppelingen.associateWith { TestTenants.koppeling("http://bron.example/$it", oin = oin) },
            modules = modules.associateWith { Any() },
        )

    /** Flags per key: for which tenants (OIN) the permission flag is on. A missing key is a missing flag. */
    private fun gateWith(
        aan: Map<String, Set<Oin>>,
        tenants: List<TenantDefinition> = listOf(tenant(a, "objectenapi", modules = setOf("berichten", "documenten-api")), tenant(b, "objectenapi", modules = setOf("berichten", "documenten-api"))),
        modules: List<ModuleFlag> = listOf(berichten, taak),
    ): ModuleGate {
        val api = OpenFeatureAPI.getInstance()
        api.setProviderAndWait(
            InMemoryProvider(
                aan.mapValues { (_, oins) ->
                    Flag
                        .builder<Boolean>()
                        .variant("on", true)
                        .variant("off", false)
                        .defaultVariant("off")
                        .contextEvaluator { _, context -> context.getValue("tenant")?.asString() in oins.map { it.value } }
                        .build()
                },
            ),
        )
        return ModuleGate(
            featureGate = OpenFeatureFeatureGate(api.client),
            featureContextFactory = FeatureContextFactory(FeatureFlagProperties.Context()),
            modules = modules,
            directory = TestTenants.directory(*tenants.toTypedArray()),
        )
    }

    private fun alle() = setOf(a, b)

    @Test
    fun `a module flag is a fail-closed permission flag named after the module`() {
        assertEquals("permission.berichten.enabled", berichten.flag.key)
        assertEquals(ToggleType.PERMISSION, berichten.flag.type)
        assertFalse(berichten.flag.default)
    }

    @Test
    fun `a module is usable when its flag is on and the tenant has the configuration`() {
        val gate = gateWith(mapOf("permission.berichten.enabled" to alle()))

        assertTrue(gate.isEnabled(berichten, a))
        assertDoesNotThrow { TestTenants.metTenant(a) { gate.require(berichten) } }
    }

    @Test
    fun `a flag that is on without configuration of the tenant means off`() {
        val gate =
            gateWith(
                mapOf("permission.berichten.enabled" to alle()),
                tenants = listOf(tenant(a, "objectenapi", modules = setOf("berichten")), tenant(b, modules = setOf("berichten"))),
            )

        assertTrue(gate.isEnabled(berichten, a))
        val zonderKoppeling = gate.beoordeel(berichten, b)
        assertTrue(zonderKoppeling.flag)
        assertFalse(zonderKoppeling.geconfigureerd)
        assertFalse(zonderKoppeling.bruikbaar)
        assertThrows<ModuleNotAvailableException> { TestTenants.metTenant(b) { gate.require(berichten) } }
    }

    @Test
    fun `a flag that is on without the module configuration of the tenant means off`() {
        val gate =
            gateWith(
                mapOf("permission.berichten.enabled" to alle()),
                tenants = listOf(tenant(a, "objectenapi")),
            )

        assertFalse(gate.isEnabled(berichten, a))
    }

    @Test
    fun `configuration without the flag is allowed but not usable`() {
        val gate = gateWith(mapOf("permission.berichten.enabled" to emptySet()))

        val beoordeling = gate.beoordeel(berichten, a)

        assertTrue(beoordeling.geconfigureerd)
        assertFalse(beoordeling.flag)
        assertFalse(beoordeling.bruikbaar)
    }

    @Test
    fun `a module is off for the tenant whose flag is off and on for the one whose flag is on`() {
        val gate = gateWith(mapOf("permission.berichten.enabled" to setOf(a)))

        assertTrue(TestTenants.metTenant(a) { gate.isEnabled(berichten) })
        assertFalse(TestTenants.metTenant(b) { gate.isEnabled(berichten) })
        assertThrows<ModuleNotAvailableException> { TestTenants.metTenant(b) { gate.require(berichten) } }
    }

    @Test
    fun `the tenant comes from the tenant context, without one the gate fails`() {
        val gate = gateWith(mapOf("permission.berichten.enabled" to alle()))

        assertThrows<TenantContextMissingException> { kotlinx.coroutines.runBlocking { gate.isEnabled(berichten) } }
    }

    @Test
    fun `a missing flag and an unknown tenant mean off`() {
        val gate = gateWith(emptyMap())

        assertFalse(gate.isEnabled(berichten, a))
        assertFalse(gate.isEnabled(berichten, Oin("00000099000000000009")))
    }

    @Test
    fun `requiring a disabled module throws a not-available error naming the module`() {
        val gate = gateWith(emptyMap())

        val error = assertThrows<ModuleNotAvailableException> { TestTenants.metTenant(a) { gate.require(berichten) } }

        assertEquals("berichten", error.module)
        assertEquals("Module 'berichten' is niet beschikbaar", error.message)
    }

    @Test
    fun `the status lists every registered module with its outcome for the tenant`() {
        val gate = gateWith(mapOf("permission.berichten.enabled" to setOf(a), "permission.taak.enabled" to alle()))

        assertEquals(mapOf("berichten" to true, "taak" to true), gate.status(a))
        assertEquals(mapOf("berichten" to false, "taak" to true), gate.status(b))
    }

    @Test
    fun `a module is usable when its own flag and all required flags and configurations hold`() {
        val gate =
            gateWith(
                mapOf("permission.afhankelijk.enabled" to alle(), "permission.documenten.enabled" to alle(), "permission.objecten.enabled" to alle()),
                modules = listOf(dependent, documenten, objecten),
            )

        assertTrue(gate.isEnabled(dependent, a))
    }

    @Test
    fun `a module is off when a required flag is off or unknown for the tenant`() {
        // OpenFeature is a JVM-wide singleton: evaluate each gate before the next one replaces the provider.
        val off = gateWith(mapOf("permission.afhankelijk.enabled" to alle(), "permission.documenten.enabled" to alle(), "permission.objecten.enabled" to setOf(b)))
        assertFalse(off.isEnabled(dependent, a))
        assertTrue(off.isEnabled(dependent, b))
        assertThrows<ModuleNotAvailableException> { TestTenants.metTenant(a) { off.require(dependent) } }

        val unknown = gateWith(mapOf("permission.afhankelijk.enabled" to alle(), "permission.documenten.enabled" to alle()))
        assertFalse(unknown.isEnabled(dependent, a))
    }

    @Test
    fun `a module is off when the tenant lacks the configuration of a required module`() {
        val gate =
            gateWith(
                mapOf("permission.afhankelijk.enabled" to alle(), "permission.documenten.enabled" to alle(), "permission.objecten.enabled" to alle()),
                tenants = listOf(tenant(a, "objectenapi")),
            )

        val beoordeling = gate.beoordeel(dependent, a)

        assertTrue(beoordeling.flag)
        assertFalse(beoordeling.geconfigureerd)
        assertFalse(beoordeling.bruikbaar)
    }

    @Test
    fun `a required flag that is on does not enable a module whose own flag is off`() {
        val gate = gateWith(mapOf("permission.documenten.enabled" to alle(), "permission.objecten.enabled" to alle()))

        assertFalse(gate.isEnabled(dependent, a))
    }

    @Test
    fun `a requirement counts through the whole chain`() {
        val gate =
            gateWith(
                mapOf(
                    "permission.keten.enabled" to alle(),
                    "permission.afhankelijk.enabled" to alle(),
                    "permission.documenten.enabled" to alle(),
                    "permission.objecten.enabled" to emptySet(),
                ),
            )

        assertFalse(gate.isEnabled(chained, a))
    }

    @Test
    fun `an endpoint without registration is denied, the core and a transitional module are let through`() {
        val gate = gateWith(emptyMap())

        TestTenants.metTenant(a) {
            assertThrows<EndpointNietGeregistreerdException> { gate.controleer(null, "Onbekend") }
            assertDoesNotThrow { gate.controleer(EndpointToewijzing.Kern, "Kern") }
            assertDoesNotThrow { gate.controleer(EndpointToewijzing.Overgang("zaken"), "Zaken") }
        }
    }

    @Test
    fun `a module endpoint passes the gate only when the module is usable for the tenant`() {
        val gate = gateWith(mapOf("permission.berichten.enabled" to setOf(a)))

        TestTenants.metTenant(a) { assertDoesNotThrow { gate.controleer(EndpointToewijzing.Module(berichten), "Berichten") } }
        assertThrows<ModuleNotAvailableException> { TestTenants.metTenant(b) { gate.controleer(EndpointToewijzing.Module(berichten), "Berichten") } }
    }

    @Test
    fun `the matrix has a row per tenant and module with configured, flag and usable`() {
        val gate =
            gateWith(
                mapOf("permission.berichten.enabled" to alle()),
                tenants = listOf(tenant(a, "objectenapi", modules = setOf("berichten")), tenant(b)),
            )

        val matrix = gate.matrix().associateBy { it.oin to it.module }

        assertEquals(4, matrix.size)
        assertTrue(matrix.getValue(a to "berichten").bruikbaar)
        assertTrue(matrix.getValue(b to "berichten").flag)
        assertFalse(matrix.getValue(b to "berichten").geconfigureerd)
        assertFalse(matrix.getValue(b to "berichten").bruikbaar)
        assertFalse(matrix.getValue(a to "taak").flag)
    }

    @Test
    fun `the dependency graph lists the required modules of every registered module, transitively`() {
        val gate = gateWith(emptyMap(), modules = listOf(chained, taak))

        assertEquals(
            mapOf("keten" to listOf("afhankelijk", "documenten", "objecten"), "taak" to emptyList()),
            gate.dependencies(),
        )
    }
}