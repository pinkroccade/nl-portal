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
package nl.nlportal.core.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * Seam: de forwarded-configuratie van de runtime. De tenantresolutie volgt zelf alleen van de vertrouwde proxies; de
 * eigen afhandeling van Spring Boot (die elk adres volgt) staat dus uit, ook op Kubernetes waar Boot hem anders aanzet.
 */
class ForwardedHeadersGuardTest {
    private fun fouten(
        vararg waarden: Pair<String, String>,
        transformer: Boolean = false,
    ) = ForwardedHeadersGuard(MockEnvironment().also { env -> waarden.forEach { env.setProperty(it.first, it.second) } }) { transformer }.controleer()

    @Test
    fun `uitgezet is goed, ook op Kubernetes`() {
        assertThat(fouten("server.forward-headers-strategy" to "none")).isEmpty()
        assertThat(fouten("server.forward-headers-strategy" to "NONE", "spring.main.cloud-platform" to "kubernetes")).isEmpty()
    }

    @Test
    fun `zonder cloudplatform en zonder instelling volgt Boot niets`() {
        assertThat(fouten()).isEmpty()
    }

    @Test
    fun `native of framework weigert het opstarten`() {
        for (strategie in listOf("native", "framework", "FRAMEWORK")) {
            assertThat(fouten("server.forward-headers-strategy" to strategie)).describedAs(strategie).hasSize(1)
        }
    }

    @Test
    fun `op Kubernetes zonder expliciete instelling zet Boot het aan en weigert het opstarten`() {
        assertThat(fouten("spring.main.cloud-platform" to "kubernetes"))
            .hasSize(1)
            .first()
            .asString()
            .contains("server.forward-headers-strategy")
    }

    @Test
    fun `een ForwardedHeaderTransformer weigert het opstarten`() {
        assertThat(fouten("server.forward-headers-strategy" to "none", transformer = true)).hasSize(1)
    }
}