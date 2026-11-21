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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

/** Wie het deed, voor zover bekend: `iss` en `sub` van het ID-token. Nooit een BSN of tokeninhoud. */
data class Actor(
    val issuer: String,
    val subject: String,
)

/** De vaste woordenschat van beveiligingsevents (ASVS 16.3.1, 16.3.3). */
enum class GebeurtenisType(
    val code: String,
) {
    LOGIN_GESLAAGD("login_geslaagd"),
    LOGIN_GEWEIGERD("login_geweigerd"),
    SESSIE_TENANT_MISMATCH("sessie_tenant_mismatch"),
    SESSIE_VERLOPEN("sessie_verlopen"),
    UITGELOGD("uitgelogd"),
    CSRF_GEWEIGERD("csrf_geweigerd"),
    ORIGIN_GEWEIGERD("origin_geweigerd"),
    ISSUER_GEWEIGERD("issuer_geweigerd"),
    LOGIN_START_GEWEIGERD("login_start_geweigerd"),
}

/** Waarom iets is geweigerd: een vaste code, geen vrije tekst (die kan een persoonsgegeven bevatten). */
enum class Reden(
    val code: String,
) {
    ISSUER("issuer"),
    CLIENT("client"),
    INLOGMIDDEL_ONBEKEND("inlogmiddel_onbekend"),
    INLOGMIDDEL_UIT("inlogmiddel_uit"),
    INLOGMIDDEL_NIET_ONDERSTEUND("inlogmiddel_niet_ondersteund"),
    VERTEGENWOORDIGING("vertegenwoordiging"),
    KVK_IDENTITEIT("kvk_identiteit"),
    NIVEAU_ONTBREEKT("niveau_ontbreekt"),
    NIVEAU_ONBEKEND("niveau_onbekend"),
    NIVEAU_TE_LAAG("niveau_te_laag"),
    BSN_ONGELDIG("bsn_ongeldig"),
    AUTH_TIME("auth_time"),
    SUBJECT("subject"),
    PROTOCOL("protocol"),
    VERLOPEN_INACTIVITEIT("inactiviteit"),
    VERLOPEN_ABSOLUUT("absoluut"),
    TENANT("tenant"),
    ORIGIN("origin"),
    CSRF("csrf"),
    INLOGMIDDEL_PARAMETER("inlogmiddel_parameter"),
    GEEN("geen"),
}

/** Een beveiligingsevent in een vast formaat: type, OIN, actor, uitkomst, reden en een correlatie-ID. */
data class Beveiligingsgebeurtenis(
    val type: GebeurtenisType,
    val oin: String?,
    val actor: Actor?,
    val uitkomst: String,
    val reden: Reden = Reden.GEEN,
    val correlatieId: String = UUID.randomUUID().toString(),
)

/** Waar events heen gaan; de standaard is de logregel, een test vangt ze op. */
fun interface Beveiligingsgebeurtenissen {
    fun registreer(gebeurtenis: Beveiligingsgebeurtenis)
}

/**
 * Schrijft elk event als één logregel met vaste velden. Waarden die niet uit een veilige tekenset bestaan worden
 * vervangen, zodat een aanvaller geen logregels kan vervalsen via bijvoorbeeld een `iss`.
 */
class LogBeveiligingsgebeurtenissen : Beveiligingsgebeurtenissen {
    override fun registreer(gebeurtenis: Beveiligingsgebeurtenis) {
        val actor = gebeurtenis.actor?.let { "${schoon(it.issuer)}|${schoon(it.subject)}" } ?: "onbekend"
        logger.warn {
            "beveiligingsevent type=${gebeurtenis.type.code} oin=${schoon(gebeurtenis.oin ?: "onbekend")} actor=$actor " +
                "uitkomst=${schoon(gebeurtenis.uitkomst)} reden=${gebeurtenis.reden.code} correlatie=${gebeurtenis.correlatieId}"
        }
    }

    internal companion object {
        private val logger = KotlinLogging.logger("nl.nlportal.beveiliging")
        private val ONGELDIG = Regex("[^A-Za-z0-9_.:/@|-]")

        internal fun schoon(waarde: String): String = ONGELDIG.replace(waarde.take(200), "_")
    }
}