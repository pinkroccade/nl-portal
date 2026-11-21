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

/** Namen en paden van de BFF op één plek (de prototype-filters herhaalden ze). */
object BffNamen {
    /** Verwijst naar de lopende login in Redis; `__Host-`, HttpOnly, Secure, `SameSite=Lax` (de callback is cross-site). */
    const val FLOW_COOKIE = "__Host-nlp-flow"

    /** De sessie na het inloggen: `__Host-Http-`, HttpOnly, Secure, `SameSite=Strict` (RFC 10017, ASVS 3.3.2). */
    const val SESSIE_COOKIE = "__Host-Http-NLP"

    /** CSRF-token voor de SPA: een leesbaar cookie dat de SPA in de `X-XSRF-TOKEN`-header terugstuurt. */
    const val CSRF_COOKIE = "__Host-XSRF-TOKEN"

    const val INLOGPAD = "/oauth2/authorization/"
    const val CALLBACKPAD = "/login/oauth2/code/"
    const val UITLOGPAD = "/logout"
    const val SESSIEPAD = "/api/public/sessie"
    const val GRAPHQLPAD = "/graphql"

    /** De enige benoemde CSRF/Origin-uitzondering (exact pad, alleen POST): de postsale-webhook met eigen handtekeningcontrole in de betaalmodule. Back-channel logout bestaat bewust niet (T24). */
    const val POSTSALE_WEBHOOK = "/api/public/payment/direct/postsale"

    /** Het veilige return-pad na het inloggen, door de pre-auth-sessie meegedragen. */
    const val RETURN_TO = "return_to"

    /** Begin van de login (voor de `auth_time`-controle van `prompt=login`). */
    const val LOGIN_START = "login_start"

    const val EXCHANGE_RETURN_TO = "nl.nlportal.bff.returnTo"
    const val EXCHANGE_UITLOG_URL = "nl.nlportal.bff.uitlogUrl"

    /** Een pad dat na het inloggen mag: relatief, één begin-slash, vaste tekenset. */
    val VEILIG_PAD = Regex("^/(?!/)[A-Za-z0-9/_.~%=&?-]*$")
}