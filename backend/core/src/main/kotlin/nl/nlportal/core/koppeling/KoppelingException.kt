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

import java.net.URI
import java.util.UUID

/** Het probleem volgens RFC 9457 (`type`, `title`, `status`, `detail`, `instance`), door de bron geleverd of door ons gesynthetiseerd. */
data class KoppelingProblem(
    val type: URI = URI.create("about:blank"),
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: URI? = null,
)

/** Wat er mis ging bij een aanroep van een koppeling; bepaalt `type`, `title` en `status` van een gesynthetiseerd probleem. */
enum class KoppelingFoutsoort(
    internal val typeNaam: String,
    internal val titel: String,
    internal val status: Int,
) {
    /** De bron antwoordde met een geldige `application/problem+json`. */
    BRON_PROBLEEM("bron-probleem", "De bron meldde een probleem", 502),

    /** De bron antwoordde met een foutstatus zonder geldige `application/problem+json`. */
    BRON_NIET_CONFORM("bron-niet-conform", "De bron antwoordde met een fout zonder probleembeschrijving", 502),

    /** Een succesvol antwoord dat leeg is of niet in het verwachte model past. */
    ANTWOORD_ONBRUIKBAAR("antwoord-onbruikbaar", "Het antwoord van de bron is onbruikbaar", 502),
    NETWERK("netwerk", "De bron is niet bereikbaar", 502),
    TIMEOUT("timeout", "De bron antwoordde niet binnen de tijd", 504),

    /** Een URL buiten de basis-URL van de koppeling; er is niets aangeroepen. */
    HOST_BINDING("host-binding", "De URL valt buiten de basis-URL van de koppeling", 502),

    /** De limiet op gelijktijdige aanroepen van deze tenant en koppeling is bereikt. */
    LIMIET("limiet", "Te veel gelijktijdige aanroepen van de koppeling", 503),

    /** De circuit breaker van deze tenant en koppeling staat open. */
    CIRCUIT_OPEN("circuit-open", "De koppeling is tijdelijk uitgeschakeld", 503),
    ;

    internal fun probleemType(): URI = URI.create("urn:nl-portal:koppeling:$typeNaam")
}

/**
 * De ene foutvorm van elke aanroep via een [KoppelingClient]. [problem] volgt RFC 9457; levert de bron geen geldige
 * problem+json (of is er geen antwoord), dan is het gesynthetiseerd uit [soort].
 *
 * Het [message] is bewust generiek: foutdetails van bronnen kunnen persoonsgegevens bevatten en mogen niet naar de
 * browser (een GraphQL-foutafhandelaar toont `message`). Alleen [problem] draagt de bronwaarden, voor code die ze nodig heeft;
 * toon die nooit aan de gebruiker. Log [problem] en de oorzaak (`cause`, met bijvoorbeeld een decodeerfout) niet en schrijf de
 * stacktrace van deze exceptie niet weg: ze kunnen persoonsgegevens of een zaak-URL bevatten. Log de [correlatieId]. De [correlatieId] staat in de log en in het bericht.
 */
class KoppelingException(
    val koppeling: String,
    val soort: KoppelingFoutsoort,
    val problem: KoppelingProblem,
    val correlatieId: String = UUID.randomUUID().toString(),
    cause: Throwable? = null,
) : RuntimeException("Koppeling '$koppeling' is niet beschikbaar (referentie $correlatieId)", cause) {
    /** De statuscode die de bron gaf of, bij een gesynthetiseerd probleem, ons eigen. */
    val status: Int get() = problem.status

    /** Telt mee voor de circuit breaker: de bron of het netwerk faalt, niet de aanvraag (een 4xx). */
    internal val isStoring: Boolean
        get() =
            when (soort) {
                KoppelingFoutsoort.NETWERK, KoppelingFoutsoort.TIMEOUT, KoppelingFoutsoort.ANTWOORD_ONBRUIKBAAR -> true
                KoppelingFoutsoort.BRON_PROBLEEM, KoppelingFoutsoort.BRON_NIET_CONFORM -> problem.status >= 500
                else -> false
            }

    internal companion object {
        fun gesynthetiseerd(
            koppeling: String,
            soort: KoppelingFoutsoort,
            status: Int = soort.status,
            cause: Throwable? = null,
        ) = KoppelingException(
            koppeling,
            soort,
            KoppelingProblem(soort.probleemType(), soort.titel, status),
            cause = cause,
        )
    }
}

/**
 * Een aanroep van een koppeling die niet kan door een fout in de eigen configuratie (bijvoorbeeld een verboden header). Dit is geen
 * bronfout: het bericht is voor de log en bevat interne gegevens, dus het gaat niet naar de browser (zie `GraphQLExceptionHandler`).
 */
class KoppelingConfiguratieException(
    message: String,
) : IllegalArgumentException(message)