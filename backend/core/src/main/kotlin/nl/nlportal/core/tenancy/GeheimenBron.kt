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

import org.springframework.core.env.Environment

class GeheimOntbreektException(
    naam: String,
) : IllegalStateException("Geheim '$naam' staat niet in de secret store")

/** De secret store zoals de runtime hem ziet: lost een [GeheimVerwijzing] op naar de waarde, of `null` als die er niet is. */
fun interface GeheimenBron {
    fun resolve(verwijzing: GeheimVerwijzing): String?

    /** De waarde, of [GeheimOntbreektException] (zonder waarde in het bericht) als het geheim ontbreekt of leeg is. */
    fun vereis(verwijzing: GeheimVerwijzing): String = resolve(verwijzing)?.takeIf { it.isNotBlank() } ?: throw GeheimOntbreektException(verwijzing.naam)
}

/**
 * Leest geheimen uit de omgeving van de runtime onder `nl-portal.geheimen.<naam>`. External Secrets Operator zet de
 * secrets daar als omgevingsvariabele neer (`NL_PORTAL_GEHEIMEN_<NAAM>`, punten en streepjes als underscore).
 */
class OmgevingGeheimenBron(
    private val omgeving: Environment,
) : GeheimenBron {
    override fun resolve(verwijzing: GeheimVerwijzing): String? = omgeving.getProperty("$PREFIX${verwijzing.naam}")

    private companion object {
        const val PREFIX = "nl-portal.geheimen."
    }
}