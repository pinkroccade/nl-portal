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

import org.springframework.http.HttpMethod
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers

/**
 * De enige uitzondering op CSRF en de Origin-regel: de postsale-webhook, alleen `POST` en exact dat pad (de betaalmodule
 * controleert de handtekening zelf). Beide regels gebruiken deze ene matcher, zodat ze nooit uit elkaar lopen.
 */
object BffUitzonderingen {
    val WEBHOOK: ServerWebExchangeMatcher = ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, BffNamen.POSTSALE_WEBHOOK)
}