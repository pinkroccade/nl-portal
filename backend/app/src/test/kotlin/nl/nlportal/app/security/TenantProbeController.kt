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
package nl.nlportal.app.security

import nl.nlportal.core.modulegate.ModuleEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import nl.nlportal.core.tenancy.TenantContext
import org.slf4j.MDC
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Probes voor de tenant-ITs: wat leest code achter de tenantresolutie via [TenantContext] en de MDC? Staat onder
 * `/api/public` zodat de security-keten ze doorlaat; alleen in de testbron.
 */
@ModuleEndpoint(ModuleEndpoint.KERN)
@RestController
class TenantProbeController {
    @GetMapping("/api/public/tenant-probe")
    suspend fun tenant(): String = TenantContext.current().value

    /** Een coroutine buiten de Reactor Context: de tenant is verloren en lezen faalt dicht. */
    @GetMapping("/api/public/tenant-verloren-probe")
    suspend fun verloren(): String =
        CoroutineScope(Dispatchers.Default)
            .async { runCatching { TenantContext.current().value }.getOrElse { "geen-tenant" } }
            .await()

    /** MDC in `suspend`-code na een dispatcherwissel. */
    @GetMapping("/api/public/mdc-suspend-probe")
    suspend fun mdcSuspend(): String = withContext(Dispatchers.IO) { MDC.get("oin") ?: "geen" }

    @GetMapping("/api/public/mdc-probe")
    fun mdc(): Mono<String> =
        Mono
            .just("x")
            .publishOn(Schedulers.boundedElastic())
            .map { MDC.get("oin") ?: "geen" }
}