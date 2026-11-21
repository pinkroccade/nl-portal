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

import com.fasterxml.jackson.annotation.JsonValue
import java.time.Duration

/** Tenantsleutel: het OIN (twintig cijfers) van de overheidsorganisatie. */
@JvmInline
value class Oin(
    val value: String,
) {
    init {
        require(value.matches(FORMAAT)) { "Een OIN bestaat uit 20 cijfers" }
    }

    override fun toString() = value

    companion object {
        const val PATROON = "^[0-9]{20}$"
        private val FORMAAT = Regex(PATROON)
    }
}

/** Een waarde die in het tenantbestand als tekst staat; [waarde] is de spelling in het bestand en in het schema. */
interface TenantbestandWaarde {
    val waarde: String
}

enum class Omgevingstype(
    @get:JsonValue override val waarde: String,
) : TenantbestandWaarde {
    PRODUCTIE("productie"),
    ACCEPTATIE("acceptatie"),
    TEST("test"),
    ONTWIKKELING("ontwikkeling"),
}

enum class Inlogmiddel(
    @get:JsonValue override val waarde: String,
) : TenantbestandWaarde {
    DIGID("digid"),
    EHERKENNING("eherkenning"),
    WALLET("wallet"),

    /** Alleen voor omgevingen die geen productie zijn. */
    TEST("test"),
}

/** Volgorde is de rangorde: een hoger niveau is betrouwbaarder. */
enum class Betrouwbaarheidsniveau(
    @get:JsonValue override val waarde: String,
) : TenantbestandWaarde {
    BASIS("basis"),
    MIDDEN("midden"),
    SUBSTANTIEEL("substantieel"),
    HOOG("hoog"),
}

/** Het uiterlijk van de gemeente; de frontend haalt dit per tenant op. */
data class Thema(
    val titel: String,
    val logo: String? = null,
    val favicon: String? = null,
    val stijl: String? = null,
)

/** Sessietijden (ADR 0022 besluit 5): een tenant mag alleen strenger instellen dan 15 minuten en 8 uur. */
data class Sessie(
    val inactiviteit: Duration = MAX_INACTIVITEIT,
    val absoluut: Duration = MAX_ABSOLUUT,
) {
    fun binnenGrenzen() =
        inactiviteit.isPositive() && inactiviteit <= MAX_INACTIVITEIT &&
            absoluut.isPositive() && absoluut <= MAX_ABSOLUUT &&
            inactiviteit <= absoluut

    private fun Duration.isPositive() = !isZero && !isNegative

    companion object {
        val MAX_INACTIVITEIT: Duration = Duration.ofMinutes(15)
        val MAX_ABSOLUUT: Duration = Duration.ofHours(8)
    }
}

/**
 * De client-registratie van de backend (de BFF) bij de Keycloak-realm van de tenant (ADR 0022). De private sleutel staat
 * niet in het tenantbestand maar in een bestand dat de secret store aanlevert (bestandsmount); alleen het pad staat hier.
 *
 * @property issuer publieke issuer van de realm; komt terug in het ID-token en in de `iss`-parameter van de callback.
 * @property sleutelBestand pad naar de private JWK (RSA, PS256, met `kid`) voor `private_key_jwt`.
 * @property clientId de enige vertrouwelijke client van de tenant.
 * @property interneBasisUrl optioneel adres van de realm voor serververkeer (token- en JWKS-endpoint) als de publieke issuer intern niet bereikbaar is.
 */
data class Authenticatie(
    val issuer: String,
    val sleutelBestand: String,
    val clientId: String = STANDAARD_CLIENT_ID,
    val interneBasisUrl: String? = null,
) {
    companion object {
        const val STANDAARD_CLIENT_ID = "nl-portal-backend"
    }
}

/**
 * De vorm van het tenantbestand, bron van het gegenereerde JSON-schema en van de validator. Bewust gescheiden van
 * [TenantDefinition]: dit is wat de beheerder schrijft, dat is wat de runtime gebruikt.
 */
data class TenantBestand(
    val oin: String,
    val naam: String,
    val omgevingstype: Omgevingstype,
    val domeinen: List<String>,
    val thema: Thema,
    val inlogmiddelen: List<Inlogmiddel>,
    val authenticatie: Authenticatie,
    val minimumNiveau: Betrouwbaarheidsniveau = Betrouwbaarheidsniveau.SUBSTANTIEEL,
    val sessie: Sessie = Sessie(),
)

/** Het onveranderlijke domeinmodel van één tenant, na validatie. */
data class TenantDefinition(
    val oin: Oin,
    val naam: String,
    val omgevingstype: Omgevingstype,
    val domeinen: List<String>,
    val thema: Thema,
    val inlogmiddelen: Set<Inlogmiddel>,
    val authenticatie: Authenticatie,
    val minimumNiveau: Betrouwbaarheidsniveau,
    val sessie: Sessie,
)