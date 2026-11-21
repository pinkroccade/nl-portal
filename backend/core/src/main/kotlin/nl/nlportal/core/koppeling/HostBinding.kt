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
package nl.nlportal.core.koppeling

import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Host-binding: een URL wordt alleen gevolgd als hij onder de basis-URL van dezelfde koppeling valt (schema, host,
 * poort en padprefix). Anders volgt een fout en gaat er niets over de lijn, dus ook geen credentials.
 */
internal object HostBinding {
    /** Een fout als [IllegalArgumentException]: de aanroeper zet hem om, met de naam van de koppeling. */
    fun oplossen(
        basis: URI,
        doel: String,
        query: Map<String, List<String>> = emptyMap(),
    ): URI {
        require(doel.none { it.isISOControl() || it == BACKSLASH || it == ' ' }) { "ongeldige tekens in de URL" }
        val kandidaat = parse(doel).let { if (it.isAbsolute) it else relatief(basis, doel) }
        require(kandidaat.rawUserInfo == null) { "gebruikersinformatie in de URL" }
        require(kandidaat.rawFragment == null) { "fragment in de URL" }
        require(kandidaat.scheme.equals(basis.scheme, ignoreCase = true)) { "ander schema" }
        require(kandidaat.host != null && kandidaat.host.equals(basis.host, ignoreCase = true)) { "andere host" }
        require(poort(kandidaat) == poort(basis)) { "andere poort" }
        val pad = kandidaat.rawPath.orEmpty()
        require(!VERBODEN_IN_PAD.containsMatchIn(pad)) { "gecodeerde scheiding of padparameter in het pad" }
        require(zonderPuntsegmenten(pad)) { "puntsegment in het pad" }
        val voorvoegsel = basis.rawPath.orEmpty().trimEnd('/')
        require(pad == voorvoegsel || pad.startsWith("$voorvoegsel/")) { "buiten het pad van de basis-URL" }
        return metQuery(kandidaat, query)
    }

    private fun parse(waarde: String): URI = runCatching { URI(waarde) }.getOrElse { throw IllegalArgumentException("ongeldige URL") }

    private fun relatief(
        basis: URI,
        doel: String,
    ): URI {
        require(!doel.startsWith("//")) { "netwerkpad in plaats van pad" }
        return parse(basis.toString().trimEnd('/') + "/" + doel.trimStart('/'))
    }

    private fun poort(uri: URI): Int =
        if (uri.port != -1) {
            uri.port
        } else if (uri.scheme.equals("https", ignoreCase = true)) {
            443
        } else {
            80
        }

    private fun zonderPuntsegmenten(rawPad: String) =
        rawPad.split('/').none {
            val gedecodeerd = UriUtils.decode(it, StandardCharsets.UTF_8)
            gedecodeerd == "." || gedecodeerd == ".."
        }

    private fun metQuery(
        uri: URI,
        query: Map<String, List<String>>,
    ): URI {
        if (query.isEmpty()) return uri
        val bouwer = UriComponentsBuilder.fromUri(uri)
        query.forEach { (naam, waarden) ->
            waarden.forEach { bouwer.queryParam(codeerQuery(naam), codeerQuery(it)) }
        }
        return bouwer.build(true).toUri()
    }

    /** `UriUtils.encodeQueryParam` laat `+` staan, en een bron leest dat als spatie. */
    private fun codeerQuery(waarde: String) = UriUtils.encodeQueryParam(waarde, StandardCharsets.UTF_8).replace("+", "%2B")

    /** Een `;` (padparameter) en een gecodeerde `/`, backslash, NUL of `%`: daarmee ontsnapt een pad aan de prefix bij een bron die ze anders leest. */
    private val VERBODEN_IN_PAD = Regex("%(2f|5c|00|25)|;", RegexOption.IGNORE_CASE)

    private const val BACKSLASH = '\\'
}