/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
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

import kotlinx.coroutines.test.runTest
import nl.nlportal.core.modulestatus.ModuleState
import nl.nlportal.catalogiapi.client.CatalogiApiClient
import nl.nlportal.core.modulestatus.ModuleStatusRegistry
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.testing.TestTenants
import nl.nlportal.core.util.ModuleNotConfiguredException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ZakenApiClientConfigurationTest {
    private val registry = ModuleStatusRegistry()

    /** Een tenant zonder koppeling `zakenapi` en zonder `modules.zakenapi`. */
    private fun client(): ZakenApiClient {
        val tenant = TestTenants.tenant()
        val register = TestTenants.register(tenant)
        return ZakenApiClient(register, TestTenants.directory(tenant), CatalogiApiClient(register), registry)
    }

    @Test
    fun `client fails clearly on use for a tenant without koppeling`() =
        runTest {
            TestTenants.alsTenant {
                assertThrows<ModuleNotConfiguredException> { client().zaken().get(UUID.randomUUID()).retrieve() }
            }
        }

    @Test
    fun `urls fail clearly without configuration`() =
        runTest {
            TestTenants.alsTenant {
                assertThrows<ModuleNotConfiguredException> { client().getZaakUrl(UUID.randomUUID()) }
                assertThrows<ModuleNotConfiguredException> { client().getZaakTypeUrl(UUID.randomUUID()) }
            }
        }

    @Test
    fun `modulegegevens fail clearly for a tenant without modules zakenapi`() =
        runTest {
            TestTenants.alsTenant {
                assertThrows<ModuleNotConfiguredException> { client().configuratie() }
            }
        }

    @Test
    fun `without a tenant there is no koppeling and no configuration, fail closed`() =
        runTest {
            assertThrows<TenantContextMissingException> { client().zaken().get(UUID.randomUUID()).retrieve() }
            assertThrows<TenantContextMissingException> { client().configuratie() }
        }

    @Test
    fun `ontbrekende configuratie komt als NOT_CONFIGURED in de modulestatus`() =
        runTest {
            TestTenants.alsTenant {
                assertThrows<ModuleNotConfiguredException> { client().zaken().get(UUID.randomUUID()).retrieve() }
            }

            val status = registry.snapshot().single()
            assertEquals("zakenapi", status.module)
            assertEquals(ModuleState.NOT_CONFIGURED, status.status)
        }
}