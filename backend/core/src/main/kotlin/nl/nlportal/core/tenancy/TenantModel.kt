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
import nl.nlportal.core.util.ModuleNotConfiguredException
import java.time.Duration
import kotlin.reflect.KClass

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
 * Verwijzing naar een geheim in de secret store, op naam. Het tenantbestand bevat nooit het geheim zelf (story 12); de naam
 * wordt bij het opstarten opgelost via [GeheimenBron]. Een eigenschap van dit type komt nooit uit de gedeelde basis (story 11).
 */
@JvmInline
value class GeheimVerwijzing(
    val naam: String,
) {
    init {
        require(naam.matches(FORMAAT)) { "Een geheimverwijzing is de naam van een geheim in de secret store, geen waarde" }
    }

    /**
     * Valt de naam onder de naamruimte van [oin]? Het eerste segment van de naam is de OIN (daarna `.`, `-` of `_`), zodat een bestand
     * nooit naar de geheimen van een andere tenant kan verwijzen (ADR 0021 besluit 4 punt 5).
     */
    fun valtOnder(oin: String) = naam.length > oin.length + 1 && naam.startsWith(oin) && naam[oin.length] in SCHEIDINGSTEKENS

    /** De naam is geen geheim; de tekstweergave laat wel zien dat het een verwijzing is. */
    override fun toString() = "geheim:$naam"

    companion object {
        const val PATROON = "^[a-z0-9]+([._-][a-z0-9]+)*\$"
        private val FORMAAT = Regex(PATROON)
        private const val SCHEIDINGSTEKENS = "._-"
    }
}

/**
 * Markeert een eigenschap van een configuratieklasse als credential of sleutelverwijzing van één tenant: de waarde komt nooit
 * uit de gedeelde basis (`base.yaml`), ook niet als het geen [GeheimVerwijzing] is (bijvoorbeeld een client-ID of sleutelpad).
 * Een eigenschap van het type [GeheimVerwijzing] is altijd niet-erfbaar, zonder deze annotatie.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class NietErfbaar

enum class TenantKoppelingAuthType(
    @get:JsonValue override val waarde: String,
) : TenantbestandWaarde {
    API_KEY("api-key"),
    BEARER("bearer"),
    ZGW_JWT("zgw-jwt"),
    OAUTH2_CLIENT_CREDENTIALS("oauth2-client-credentials"),
    OAUTH2_PRIVATE_KEY_JWT("oauth2-private-key-jwt"),

    /** Bewust geen authenticatie door de app: de bron zit achter de cloud gateway, die per gemeente zelf authenticeert (mTLS). */
    GEEN("geen"),
}

/** Authenticatie van een koppeling; welke velden verplicht zijn hangt van [type] af (zie [ontbrekendeVelden]). */
data class TenantKoppelingAuth(
    val type: TenantKoppelingAuthType,
    val headerNaam: String? = null,
    val waarde: GeheimVerwijzing? = null,
    val token: GeheimVerwijzing? = null,
    @NietErfbaar val clientId: String? = null,
    val secret: GeheimVerwijzing? = null,
    val tokenUrl: String? = null,
    @NietErfbaar val privateKeyBestand: String? = null,
) {
    /** De namen (zoals in het tenantbestand) van de verplichte velden die voor [type] nog ontbreken. */
    fun ontbrekendeVelden(): List<String> =
        when (type) {
            TenantKoppelingAuthType.API_KEY -> listOf("headerNaam" to headerNaam, "waarde" to waarde)
            TenantKoppelingAuthType.BEARER -> listOf("token" to token)
            TenantKoppelingAuthType.ZGW_JWT -> listOf("clientId" to clientId, "secret" to secret)
            TenantKoppelingAuthType.OAUTH2_CLIENT_CREDENTIALS -> listOf("tokenUrl" to tokenUrl, "clientId" to clientId, "secret" to secret)
            TenantKoppelingAuthType.OAUTH2_PRIVATE_KEY_JWT -> listOf("tokenUrl" to tokenUrl, "clientId" to clientId, "privateKeyBestand" to privateKeyBestand)
            TenantKoppelingAuthType.GEEN -> emptyList()
        }.filter { (_, waarde) -> waarde == null || (waarde is String && waarde.isBlank()) }.map { it.first }

    /** Geen geheim in de tekstweergave: alleen of een verwijzing is gezet. */
    override fun toString() = "TenantKoppelingAuth(type=$type, headerNaam=$headerNaam, clientId=$clientId, tokenUrl=$tokenUrl)"
}

/**
 * Een koppeling van een tenant naar een bronsysteem, zoals die in het tenantbestand staat (`koppelingen.<naam>`). De naam is
 * de modulenaam, of een eigen naam voor een tweede koppeling van dezelfde module (`registry.current(module, naam)`).
 */
data class TenantKoppeling(
    val url: String,
    val auth: TenantKoppelingAuth,
    val leverancier: String? = null,
    val versie: String? = null,
)

/**
 * De vorm van het tenantbestand, bron van het gegenereerde JSON-schema en van de validator. Bewust gescheiden van
 * [TenantDefinition]: dit is wat de beheerder schrijft, dat is wat de runtime gebruikt.
 *
 * [modules] is `modules.<naam>`: de configuratie die een module via [TenantModuleConfiguratie] bijdraagt. De typen staan niet
 * hier maar bij de module; schema en loader lezen ze via de SPI.
 */
data class TenantBestand(
    val oin: String,
    val naam: String,
    val omgevingstype: Omgevingstype,
    val domeinen: List<String>,
    val thema: Thema,
    val inlogmiddelen: List<Inlogmiddel>,
    val minimumNiveau: Betrouwbaarheidsniveau = Betrouwbaarheidsniveau.SUBSTANTIEEL,
    val sessie: Sessie = Sessie(),
    val koppelingen: Map<String, TenantKoppeling> = emptyMap(),
    val modules: Map<String, Any?> = emptyMap(),
)

/** Het onveranderlijke domeinmodel van één tenant, na validatie. */
data class TenantDefinition(
    val oin: Oin,
    val naam: String,
    val omgevingstype: Omgevingstype,
    val domeinen: List<String>,
    val thema: Thema,
    val inlogmiddelen: Set<Inlogmiddel>,
    val minimumNiveau: Betrouwbaarheidsniveau,
    val sessie: Sessie,
    val koppelingen: Map<String, TenantKoppeling> = emptyMap(),
    /** De gebonden en gevalideerde modulegegevens per modulenaam; lees ze via [module]. */
    val modules: Map<String, Any> = emptyMap(),
) {
    /** De configuratie van de module met precies de klasse [klasse] voor deze tenant, of `null` als de tenant er geen heeft. */
    fun <T : Any> module(klasse: KClass<T>): T? = modules.values.firstOrNull { it::class == klasse }?.let { klasse.javaObjectType.cast(it) }

    inline fun <reified T : Any> module(): T? = module(T::class)

    /** Als [module], maar een tenant zonder configuratie voor de module is een fout: de module is voor deze tenant niet ingericht. */
    fun <T : Any> requireModule(klasse: KClass<T>): T = module(klasse) ?: throw ModuleNotConfiguredException(klasse.simpleName ?: "module", "Tenant $oin: modulegegevens")

    inline fun <reified T : Any> requireModule(): T = requireModule(T::class)
}