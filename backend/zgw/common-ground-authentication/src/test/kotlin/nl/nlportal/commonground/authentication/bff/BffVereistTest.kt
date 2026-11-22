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
package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.tenancy.TenantDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** Zonder tenancy valt de login niet stil weg: waar de BFF verplicht is, weigert het opstarten (fail-closed). */
class BffVereistTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BffVereistAutoConfiguration::class.java))

    @Test
    fun `een verplichte BFF zonder tenancy weigert het opstarten`() {
        runner.withPropertyValues("nl-portal.authentication.bff.vereist=true").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasRootCauseInstanceOf(IllegalStateException::class.java).hasStackTraceContaining("nl-portal.tenancy.directory")
        }
    }

    @Test
    fun `een verplichte BFF met tenancy start`() {
        runner.withBean(TenantDirectory::class.java, { TenantDirectory(emptyList()) }).withPropertyValues("nl-portal.authentication.bff.vereist=true").run {
            assertThat(it).hasNotFailed()
        }
    }

    @Test
    fun `zonder de eis start een context zonder tenancy gewoon`() {
        runner.run { assertThat(it).hasNotFailed() }
    }
}

/** De webhook-uitzondering heeft één bron voor CSRF en Origin-regel. */
class BffUitzonderingenTest {
    private fun matcht(
        methode: String,
        pad: String,
    ) = BffUitzonderingen.WEBHOOK
        .matches(
            org.springframework.mock.web.server.MockServerWebExchange
                .from(
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .method(
                            org.springframework.http.HttpMethod
                                .valueOf(methode),
                            pad,
                        ).build(),
                ),
        ).block()!!
        .isMatch

    @Test
    fun `alleen POST op exact het webhookpad valt onder de uitzondering`() {
        assertThat(matcht("POST", BffNamen.POSTSALE_WEBHOOK)).isTrue()
        assertThat(matcht("GET", BffNamen.POSTSALE_WEBHOOK)).isFalse()
        assertThat(matcht("POST", "${BffNamen.POSTSALE_WEBHOOK}/extra")).isFalse()
    }
}