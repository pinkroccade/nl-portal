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

import nl.nlportal.core.tenancy.TenantFileViolation.Code
import java.net.URI
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

/**
 * Een schending van een regel voor koppelingen of moduleconfiguratie. [pad] is het pad in het tenantbestand en [bericht] bevat
 * nooit een waarde en nog niet de OIN: de loader en de opstartcontrole zetten `Tenant <oin>:` ervoor (story 14).
 */
internal data class Regelovertreding(
    val code: Code,
    val pad: String,
    val bericht: String,
)

private val KOPPELINGNAAM = Regex("^[a-z][a-z0-9-]*$")
private val OIN_FORMAAT = Regex(Oin.PATROON)

/** Headers die het transport bepalen: een API-sleutel hoort daar niet in. */
private val VERBODEN_HEADERS = setOf("host", "cookie", "content-length", "transfer-encoding")
private val HTTP_TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

/**
 * De regels voor een koppeling in het tenantbestand (`koppelingen.<naam>`): naam, url, auth, headernaam, naamruimte van de geheimen
 * en het sleutelbestand. De loader past ze toe bij het laden en de validator-CLI; de opstartbouw van het register past ze opnieuw
 * toe, zodat ook een tenant die niet uit de loader komt (testhulp) dezelfde regels kent.
 */
internal fun TenantKoppeling.regelovertredingen(
    naam: String,
    oin: String,
    omgevingstype: Omgevingstype,
): List<Regelovertreding> {
    val pad = "koppelingen.$naam"
    val overtredingen = mutableListOf<Regelovertreding>()
    if (!naam.matches(KOPPELINGNAAM)) {
        overtredingen += Regelovertreding(Code.ONGELDIGE_WAARDE, pad, "De naam van een koppeling bestaat uit kleine letters, cijfers en streepjes en begint met een letter")
    }
    controleerUrl(url, "$pad.url", "de url", omgevingstype)?.let { overtredingen += it }
    auth.tokenUrl?.let { controleerUrl(it, "$pad.auth.tokenUrl", "de tokenUrl", omgevingstype) }?.let { overtredingen += it }
    auth.headerNaam?.takeUnless(::isToegestaneHeader)?.let {
        overtredingen += Regelovertreding(Code.ONGELDIGE_WAARDE, "$pad.auth.headerNaam", "Een headernaam is een geldig HTTP-token en niet Host, Cookie, Content-Length of Transfer-Encoding")
    }
    auth.ontbrekendeVelden().forEach {
        overtredingen += Regelovertreding(Code.KOPPELING_ONVOLLEDIG, "$pad.auth.$it", "koppeling '$naam' is onvolledig, ${auth.type.waarde} vraagt om auth.$it")
    }
    overtredingen += geheimVerwijzingen(this, pad).filterNot { (_, verwijzing) -> verwijzing.valtOnder(oin) }.map { (geheimPad, _) -> buitenNaamruimte(geheimPad) }
    auth.privateKeyBestand?.takeUnless { sleutelbestandValtOnder(it, oin) }?.let {
        overtredingen += Regelovertreding(Code.GEHEIM_VAN_ANDERE_TENANT, "$pad.auth.privateKeyBestand", "Een sleutelbestand staat in een map met de OIN van deze tenant ($oin) als pad-segment, en in geen andere OIN-map, zonder '..'")
    }
    return overtredingen
}

/** Een geheimverwijzing die niet met de OIN van de tenant begint; het bericht noemt de naam niet (kan een geheim zijn). */
internal fun buitenNaamruimte(pad: String) = Regelovertreding(Code.GEHEIM_VAN_ANDERE_TENANT, pad, "Een geheimverwijzing begint met de OIN van deze tenant, gevolgd door . - of _ en de naam; een andere naamruimte is niet toegestaan")

private fun controleerUrl(
    url: String,
    pad: String,
    omschrijving: String,
    omgevingstype: Omgevingstype,
): Regelovertreding? {
    val uri = runCatching { URI(url) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    return when {
        uri == null || scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null -> {
            Regelovertreding(Code.ONGELDIGE_WAARDE, pad, "$omschrijving is geen http(s)-url met een host en zonder inloggegevens")
        }

        scheme == "http" && omgevingstype == Omgevingstype.PRODUCTIE -> {
            Regelovertreding(Code.ONGELDIGE_WAARDE, pad, "$omschrijving moet in productie https zijn: gegevens en credentials gaan nooit onversleuteld over het netwerk")
        }

        else -> {
            null
        }
    }
}

private fun isToegestaneHeader(naam: String) = naam.matches(HTTP_TOKEN) && naam.lowercase() !in VERBODEN_HEADERS

/**
 * Valt het pad van een sleutelbestand onder de naamruimte van [oin]? Dat is zo als precies een pad-segment de OIN is (de eigen),
 * er geen andere OIN-map in staat, geen `..` en de OIN niet de bestandsnaam zelf is. Beide scheidingstekens tellen (Windows en Linux).
 */
internal fun sleutelbestandValtOnder(
    pad: String,
    oin: String,
): Boolean {
    val segmenten = pad.split('/', '\\').filter { it.isNotEmpty() }
    return ".." !in segmenten && segmenten.filter { it.matches(OIN_FORMAAT) } == listOf(oin) && segmenten.last() != oin
}

/**
 * De geheimverwijzingen in [waarde] met hun pad in het tenantbestand: een [GeheimVerwijzing] zelf, of die in een dataclass, lijst,
 * verzameling of map, op elke diepte. Lijsten geven `[index]`, maps `.sleutel`. Eén wandeling voor elke regel over geheimen.
 */
internal fun geheimVerwijzingen(
    waarde: Any?,
    pad: String,
): List<Pair<String, GeheimVerwijzing>> =
    when {
        waarde == null -> emptyList()
        waarde is GeheimVerwijzing -> listOf(pad to waarde)
        waarde is Map<*, *> -> waarde.flatMap { (sleutel, inhoud) -> geheimVerwijzingen(inhoud, "$pad.$sleutel") }
        waarde is Iterable<*> -> waarde.flatMapIndexed { index, inhoud -> geheimVerwijzingen(inhoud, "$pad[$index]") }
        waarde::class.isData -> dataclassEigenschappen(waarde).flatMap { (naam, inhoud) -> geheimVerwijzingen(inhoud, "$pad.$naam") }
        else -> emptyList()
    }

private fun dataclassEigenschappen(waarde: Any): List<Pair<String, Any?>> {
    val klasse: KClass<out Any> = waarde::class
    val namen =
        klasse.primaryConstructor
            ?.parameters
            ?.mapNotNull { it.name }
            .orEmpty()
    return klasse.memberProperties.filter { it.name in namen }.map { eigenschap ->
        eigenschap.isAccessible = true
        // Het veld, niet de getter: kotlin-reflect geeft voor een lege GeheimVerwijzing? een omhulsel om null in plaats van null.
        val ruw = eigenschap.javaField?.apply { isAccessible = true }?.get(waarde) ?: eigenschap.getter.call(waarde)
        eigenschap.name to if (eigenschap.returnType.classifier == GeheimVerwijzing::class) (ruw as? String)?.let(::GeheimVerwijzing) else ruw
    }
}

/**
 * De opstartcontrole van één tenant, vóór het register wordt gebouwd: de regels voor koppelingen, de naamruimte van de geheimen in
 * `modules` en het oplossen van elk geheim in `modules` via [bron] (story 14 en 30: het register is bij opstarten volledig, een
 * ontbrekend geheim komt niet pas bij het eerste request aan het licht). De melding noemt veld en OIN, nooit de naam of waarde.
 */
internal fun TenantDefinition.controleerOpstart(bron: GeheimenBron) {
    val overtredingen =
        koppelingen.flatMap { (naam, koppeling) -> koppeling.regelovertredingen(naam, oin.value, omgevingstype) } +
            modules
                .flatMap { (naam, configuratie) -> geheimVerwijzingen(configuratie, "modules.$naam") }
                .filterNot { (_, verwijzing) -> verwijzing.valtOnder(oin.value) }
                .map { (pad, _) -> buitenNaamruimte(pad) }
    check(overtredingen.isEmpty()) { overtredingen.joinToString("\n") { "Tenant ${oin.value}: ${it.pad}: ${it.bericht}" } }
    modules.flatMap { (naam, configuratie) -> geheimVerwijzingen(configuratie, "modules.$naam") }.forEach { (pad, verwijzing) ->
        try {
            bron.vereis(verwijzing)
        } catch (e: GeheimOntbreektException) {
            throw IllegalStateException("Tenant ${oin.value}: $pad: geheim staat niet in de secret store of is leeg", e)
        }
    }
}