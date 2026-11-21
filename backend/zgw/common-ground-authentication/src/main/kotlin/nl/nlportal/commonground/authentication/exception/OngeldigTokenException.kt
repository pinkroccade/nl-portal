/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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
package nl.nlportal.commonground.authentication.exception

import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.server.resource.BearerTokenErrors

/**
 * Een token waaruit geen bruikbare identiteit te lezen is. Het is een [OAuth2AuthenticationException], zodat de
 * resource server met `401` en `invalid_token` antwoordt (RFC 6750 paragraaf 3.1) in plaats van met een `500`.
 * De melding komt in de `WWW-Authenticate`-header terecht; zet er dus geen claimwaarden in.
 */
open class OngeldigTokenException(
    msg: String,
) : OAuth2AuthenticationException(BearerTokenErrors.invalidToken(msg))
