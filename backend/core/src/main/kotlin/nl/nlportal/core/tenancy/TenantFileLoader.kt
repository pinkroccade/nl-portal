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
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/** Een reden om een tenantbestand te weigeren. Bevat nooit de waarde zelf, want dat kan een geheim zijn. */
data class TenantFileViolation(
    val code: Code,
    val bestand: String,
    val pad: String?,
    val bericht: String,
) {
    enum class Code {
        GEEN_TENANTBESTANDEN,
        ONLEESBAAR,
        OIN_NIET_GEQUOTE,
        VERPLICHTE_WAARDE_ONTBREEKT,
        ONBEKENDE_EIGENSCHAP,
        ONGELDIGE_WAARDE,
        ONGELDIG_DOMEIN,
        DUBBEL_DOMEIN,
        DUBBELE_OIN,
        BESTANDSNAAM_KLOPT_NIET_MET_OIN,
        TESTINLOGMIDDEL_IN_PRODUCTIE,
        MINIMUMNIVEAU_TE_LAAG_IN_PRODUCTIE,
        MEERDERE_BASISBESTANDEN,
        SESSIETIJD_BUITEN_GRENZEN,
        NIET_ERFBARE_WAARDE_IN_BASIS,
        TWEEDE_TENANT_NOG_NIET_TOEGESTAAN,
        DUBBELE_ISSUER,
        DUBBEL_SLEUTELBESTAND,
        GEHEIM_NIET_ALS_VERWIJZING,
        KOPPELING_ONVOLLEDIG,
        MODULE_CONFIGURATIE_ONGELDIG,
        GEHEIM_VAN_ANDERE_TENANT,
    }

    override fun toString() = "$bestand${pad?.let { " ($it)" } ?: ""}: $code - $bericht"
}

data class TenantFileResult(
    val tenants: List<TenantDefinition>,
    val violations: List<TenantFileViolation>,
) {
    fun orThrow(): List<TenantDefinition> = if (violations.isEmpty()) tenants else throw InvalidTenantConfigurationException(violations)
}

class InvalidTenantConfigurationException(
    val violations: List<TenantFileViolation>,
) : IllegalStateException("Ongeldige tenantbestanden:\n" + violations.joinToString("\n") { " - $it" })

/**
 * Laadt de tenantbestanden uit een map: `base.yaml` (gedeelde basis van de omgeving, optioneel) en per tenant
 * `<oin>.yaml`, dat de basis overschrijft. Dezelfde code draait bij opstarten en als CLI ([TenantValidatorCli]).
 *
 * @param meerdereTenantsToegestaan blijft `false` tot alle modules hun configuratie per tenant lezen (epic #499, stap 7).
 */
class TenantFileLoader(
    private val meerdereTenantsToegestaan: Boolean = MEERDERE_TENANTS_TOEGESTAAN,
    private val modules: TenantModules = TenantModules.ontdek(),
) {
    private val model = TenantSchemaModel(modules)

    fun load(directory: Path): TenantFileResult {
        val violations = mutableListOf<TenantFileViolation>()
        val bestanden =
            if (Files.isDirectory(directory)) {
                Files.list(directory).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension in setOf("yaml", "yml") }.sorted().toList()
                }
            } else {
                emptyList()
            }
        val (basisBestanden, tenantBestanden) = bestanden.partition { it.nameWithoutExtension == BASIS }
        if (tenantBestanden.isEmpty()) {
            return TenantFileResult(emptyList(), listOf(TenantFileViolation(Code.GEEN_TENANTBESTANDEN, directory.fileName?.toString() ?: ".", null, "Geen tenantbestanden gevonden")))
        }

        if (basisBestanden.size > 1) {
            violations += TenantFileViolation(Code.MEERDERE_BASISBESTANDEN, basisBestanden[1].fileName.toString(), null, "Er is één basisbestand; ${basisBestanden[0].fileName} staat er al naast")
        }
        val basis = basisBestanden.firstOrNull()?.let { leesBasis(it, violations) } ?: emptyMap()
        val tenants = tenantBestanden.mapNotNull { leesTenant(it, basis, violations) }

        controleerOverTenants(tenants, violations)
        return TenantFileResult(tenants.map { it.definition }, violations)
    }

    private fun leesBasis(
        pad: Path,
        violations: MutableList<TenantFileViolation>,
    ): Map<String, Any?>? {
        val naam = pad.fileName.toString()
        val map = parse(pad, violations) ?: return null
        val voor = violations.size
        controleerEigenschappen(model.tenantBestand, map, "", naam, violations, verplicht = false)
        controleerNietErfbaar(model.tenantBestand, map, "", naam, violations)
        NIET_ERFBAAR.filter { it in map }.forEach {
            violations += TenantFileViolation(Code.NIET_ERFBARE_WAARDE_IN_BASIS, naam, it, "Dit hoort bij één tenant en kan niet uit de gedeelde basis komen")
        }
        return if (violations.size == voor) map else null
    }

    private fun leesTenant(
        pad: Path,
        basis: Map<String, Any?>,
        violations: MutableList<TenantFileViolation>,
    ): Geladen? {
        val naam = pad.fileName.toString()
        val eigen = parse(pad, violations) ?: return null
        val voor = violations.size

        val oin = eigen["oin"]
        if (oin != null && oin !is String) {
            violations += TenantFileViolation(Code.OIN_NIET_GEQUOTE, naam, "oin", "Een OIN moet tussen aanhalingstekens staan; zonder worden voorloopnullen of cijfers verkeerd gelezen")
            return null
        }

        val samengevoegd = voegSamen(basis, eigen)
        controleerEigenschappen(model.tenantBestand, samengevoegd, "", naam, violations, verplicht = true)
        if (violations.size != voor) {
            metOin(violations, voor, samengevoegd["oin"] as? String)
            return null
        }

        val bestand =
            try {
                mapper.convertValue(samengevoegd, TenantBestand::class.java)
            } catch (e: JacksonException) {
                violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, e.path.joinToString(".") { it.propertyName ?: "[]" }.ifEmpty { null }, "Een waarde heeft niet de verwachte vorm")
                metOin(violations, voor, samengevoegd["oin"] as? String)
                return null
            }
        return controleerBestand(naam, pad.nameWithoutExtension, bestand, violations)?.let { Geladen(naam, it) }
    }

    private fun controleerBestand(
        naam: String,
        bestandsnaam: String,
        bestand: TenantBestand,
        violations: MutableList<TenantFileViolation>,
    ): TenantDefinition? {
        if (!bestand.oin.matches(Regex(Oin.PATROON))) {
            violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, "oin", "Een OIN bestaat uit 20 cijfers")
            return null
        }
        if (bestand.oin != bestandsnaam) {
            violations += TenantFileViolation(Code.BESTANDSNAAM_KLOPT_NIET_MET_OIN, naam, "oin", "Het bestand moet <oin>.yaml heten")
        }
        val voor = violations.size
        controleerDomeinen(naam, bestand, violations)
        controleerInlogmiddelen(naam, bestand, violations)
        controleerSessie(naam, bestand.sessie, violations)
        controleerAuthenticatie(naam, bestand, violations)
        controleerKoppelingen(naam, bestand, violations)
        val gebonden = bindModules(naam, bestand, violations)
        metOin(violations, voor, bestand.oin)
        if (violations.size != voor) return null

        return TenantDefinition(
            oin = Oin(bestand.oin),
            naam = bestand.naam,
            omgevingstype = bestand.omgevingstype,
            domeinen = bestand.domeinen,
            thema = bestand.thema,
            inlogmiddelen = bestand.inlogmiddelen.toSet(),
            authenticatie = bestand.authenticatie,
            minimumNiveau = bestand.minimumNiveau,
            sessie = bestand.sessie,
            koppelingen = bestand.koppelingen,
            modules = gebonden,
        )
    }

    private fun controleerDomeinen(
        naam: String,
        bestand: TenantBestand,
        violations: MutableList<TenantFileViolation>,
    ) {
        if (bestand.domeinen.isEmpty()) violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, "domeinen", "Minstens één domein is verplicht")
        bestand.domeinen.filterNot { it.matches(HOSTNAAM) }.forEach {
            violations += TenantFileViolation(Code.ONGELDIG_DOMEIN, naam, "domeinen", "Een domein is een hostnaam in kleine letters, zonder poort, trailing dot of IP-adres")
        }
    }

    private fun controleerInlogmiddelen(
        naam: String,
        bestand: TenantBestand,
        violations: MutableList<TenantFileViolation>,
    ) {
        if (bestand.inlogmiddelen.isEmpty()) violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, "inlogmiddelen", "Minstens één inlogmiddel is verplicht")
        if (bestand.omgevingstype != Omgevingstype.PRODUCTIE) return
        if (Inlogmiddel.TEST in bestand.inlogmiddelen) {
            violations += TenantFileViolation(Code.TESTINLOGMIDDEL_IN_PRODUCTIE, naam, "inlogmiddelen", "Het testinlogmiddel is niet toegestaan in een productieomgeving")
        }
        if (bestand.minimumNiveau < Betrouwbaarheidsniveau.SUBSTANTIEEL) {
            violations += TenantFileViolation(Code.MINIMUMNIVEAU_TE_LAAG_IN_PRODUCTIE, naam, "minimumNiveau", "In een productieomgeving is het minimumniveau minstens substantieel")
        }
    }

    private fun controleerKoppelingen(
        naam: String,
        bestand: TenantBestand,
        violations: MutableList<TenantFileViolation>,
    ) {
        bestand.koppelingen.forEach { (koppeling, definitie) ->
            definitie.regelovertredingen(koppeling, bestand.oin, bestand.omgevingstype).forEach { violations += it.alsViolation(naam) }
        }
    }

    private fun Regelovertreding.alsViolation(bestand: String) = TenantFileViolation(code, bestand, pad, bericht)

    /** Bindt de ruwe `modules.<naam>` aan de configuratieklasse van de module en past de regels van de module toe. */
    private fun bindModules(
        naam: String,
        bestand: TenantBestand,
        violations: MutableList<TenantFileViolation>,
    ): Map<String, Any> =
        bestand.modules
            .mapNotNull { (module, ruw) ->
                val pad = "modules.$module"
                val spi = modules.configuratie(module) ?: return@mapNotNull null
                if (ruw == null) {
                    violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, pad, "de configuratie van module '$module' is leeg")
                    return@mapNotNull null
                }
                val configuratie =
                    try {
                        mapper.convertValue(ruw, spi.klasse.java)
                    } catch (e: JacksonException) {
                        val veld = e.path.joinToString(".") { it.propertyName ?: "[]" }
                        violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, if (veld.isEmpty()) pad else "$pad.$veld", "een waarde heeft niet de verwachte vorm")
                        return@mapNotNull null
                    }
                geheimVerwijzingen(configuratie, pad).filterNot { (_, verwijzing) -> verwijzing.valtOnder(bestand.oin) }.forEach { (geheimPad, _) ->
                    violations += buitenNaamruimte(geheimPad).alsViolation(naam)
                }
                modules.valideer(module, configuratie, ModuleContext(bestand.koppelingen.keys)).forEach {
                    violations += TenantFileViolation(Code.MODULE_CONFIGURATIE_ONGELDIG, naam, "$pad.${it.pad}".trimEnd('.'), it.bericht)
                }
                module to configuratie
            }.toMap()

    /** De enige plek die de OIN van de tenant in het bericht zet van de overtredingen vanaf [vanaf] onder `koppelingen` en `modules` (story 14). */
    private fun metOin(
        violations: MutableList<TenantFileViolation>,
        vanaf: Int,
        oin: String?,
    ) {
        if (oin == null) return
        for (i in vanaf until violations.size) {
            val v = violations[i]
            if (v.pad?.startsWith("koppelingen") == true || v.pad?.startsWith("modules") == true) {
                violations[i] = v.copy(bericht = "Tenant $oin: ${v.bericht}")
            }
        }
    }

    /** De issuer is een absolute https-URL (http alleen in ontwikkeling) zonder userinfo, query, fragment of slot-slash. */
    private fun controleerAuthenticatie(
        naam: String,
        bestand: TenantBestand,
        violations: MutableList<TenantFileViolation>,
    ) {
        val authenticatie = bestand.authenticatie
        val ontwikkeling = bestand.omgevingstype == Omgevingstype.ONTWIKKELING
        if (!veiligeUrl(authenticatie.issuer, ontwikkeling, slotSlashToegestaan = false)) {
            violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, "authenticatie.issuer", "De issuer is een absolute https-URL (http alleen in ontwikkeling) zonder gebruikersinformatie, query, fragment of slot-slash")
        }
        authenticatie.interneBasisUrl?.let {
            if (!veiligeUrl(it, http = true, slotSlashToegestaan = false)) {
                violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, "authenticatie.interneBasisUrl", "De interne basis-URL is een absolute http(s)-URL zonder gebruikersinformatie, query, fragment of slot-slash")
            }
        }
    }

    private fun veiligeUrl(
        waarde: String,
        http: Boolean,
        slotSlashToegestaan: Boolean,
    ): Boolean {
        val uri = runCatching { java.net.URI(waarde) }.getOrNull() ?: return false
        val schemaOk = uri.scheme == "https" || (http && uri.scheme == "http")
        return schemaOk && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (slotSlashToegestaan || !uri.rawPath.orEmpty().endsWith("/"))
    }

    private fun controleerSessie(
        naam: String,
        sessie: Sessie,
        violations: MutableList<TenantFileViolation>,
    ) {
        if (!sessie.binnenGrenzen()) {
            violations += TenantFileViolation(Code.SESSIETIJD_BUITEN_GRENZEN, naam, "sessie", "Inactiviteit hoogstens 15 minuten, absoluut hoogstens 8 uur, inactiviteit niet langer dan absoluut, beide positief")
        }
    }

    private fun controleerOverTenants(
        tenants: List<Geladen>,
        violations: MutableList<TenantFileViolation>,
    ) {
        if (tenants.size > 1 && !meerdereTenantsToegestaan) {
            violations += TenantFileViolation(Code.TWEEDE_TENANT_NOG_NIET_TOEGESTAAN, tenants[1].bestand, null, "Nog niet alle modules lezen hun configuratie per tenant; één tenant per runtime")
        }
        tenants.groupBy { it.definition.oin }.filterValues { it.size > 1 }.forEach { (oin, dubbel) ->
            violations += TenantFileViolation(Code.DUBBELE_OIN, dubbel[1].bestand, "oin", "OIN $oin staat ook in ${dubbel[0].bestand}")
        }
        controleerUniek(tenants, violations, Code.DUBBELE_ISSUER, "authenticatie.issuer", "Issuer") { it.definition.authenticatie.issuer }
        controleerUniek(tenants, violations, Code.DUBBEL_SLEUTELBESTAND, "authenticatie.sleutelBestand", "Sleutelbestand") { it.definition.authenticatie.sleutelBestand }
        val eigenaar = mutableMapOf<String, Geladen>()
        tenants.forEach { tenant ->
            tenant.definition.domeinen.distinct().forEach { domein ->
                val eerder = eigenaar.putIfAbsent(domein, tenant)
                if (eerder != null) {
                    violations += TenantFileViolation(Code.DUBBEL_DOMEIN, tenant.bestand, "domeinen", "Domein $domein staat ook in ${eerder.bestand}")
                }
            }
        }
    }

    private fun controleerUniek(
        tenants: List<Geladen>,
        violations: MutableList<TenantFileViolation>,
        code: Code,
        pad: String,
        soort: String,
        waarde: (Geladen) -> String,
    ) {
        val eerder = mutableMapOf<String, Geladen>()
        tenants.forEach { tenant ->
            val ander = eerder.putIfAbsent(waarde(tenant), tenant)
            if (ander != null) {
                // de waarde zelf staat bewust niet in de melding: een pad of issuer kan een geheim verraden
                violations += TenantFileViolation(code, tenant.bestand, pad, "$soort wordt al gebruikt in ${ander.bestand}; elke tenant heeft een eigen waarde")
            }
        }
    }

    private fun parse(
        pad: Path,
        violations: MutableList<TenantFileViolation>,
    ): Map<String, Any?>? {
        val naam = pad.fileName.toString()
        val opties =
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 10
                codePointLimit = MAX_BESTAND
            }
        return try {
            when (val waarde = Yaml(SafeConstructor(opties)).load<Any?>(Files.readString(pad))) {
                null -> {
                    emptyMap()
                }

                is Map<*, *> -> {
                    if (waarde.keys.any { it !is String }) {
                        violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, naam, null, "Elke sleutel is tekst")
                        return null
                    }
                    @Suppress("UNCHECKED_CAST")
                    waarde as Map<String, Any?>
                }

                else -> {
                    violations += TenantFileViolation(Code.ONLEESBAAR, naam, null, "De inhoud is geen map van sleutels en waarden")
                    null
                }
            }
        } catch (e: YAMLException) {
            val regel = (e as? MarkedYAMLException)?.problemMark?.line?.plus(1)
            violations += TenantFileViolation(Code.ONLEESBAAR, naam, null, "Geen geldige YAML${regel?.let { " (regel $it)" } ?: ""}")
            null
        } catch (e: java.io.IOException) {
            violations += TenantFileViolation(Code.ONLEESBAAR, naam, null, "Het bestand is niet te lezen")
            null
        }
    }

    /** Controleert de ruwe waarde tegen het schema: onbekende en ontbrekende eigenschappen, en de vorm van elke waarde. */
    private fun controleerEigenschappen(
        knoop: SchemaKnoop.Object,
        waarde: Map<String, Any?>,
        pad: String,
        bestand: String,
        violations: MutableList<TenantFileViolation>,
        verplicht: Boolean,
    ) {
        waarde.forEach { (sleutel, inhoud) ->
            val eigenschapPad = if (pad.isEmpty()) sleutel else "$pad.$sleutel"
            val eigenschap = knoop.eigenschappen[sleutel]
            if (eigenschap == null) {
                violations += TenantFileViolation(Code.ONBEKENDE_EIGENSCHAP, bestand, eigenschapPad, "Onbekende eigenschap; het tenantbestand bevat alleen de eigenschappen uit het schema")
            } else {
                controleerKnoop(eigenschap.knoop, inhoud, eigenschapPad, bestand, violations, verplicht)
            }
        }
        if (verplicht) {
            knoop.eigenschappen.filter { (sleutel, eigenschap) -> eigenschap.verplicht && waarde[sleutel] == null }.keys.forEach {
                violations += TenantFileViolation(Code.VERPLICHTE_WAARDE_ONTBREEKT, bestand, if (pad.isEmpty()) it else "$pad.$it", "Verplichte waarde ontbreekt")
            }
        }
    }

    private fun controleerKnoop(
        knoop: SchemaKnoop,
        inhoud: Any?,
        pad: String,
        bestand: String,
        violations: MutableList<TenantFileViolation>,
        verplicht: Boolean,
    ) {
        val goed =
            when (knoop) {
                is SchemaKnoop.Object -> {
                    if (inhoud is Map<*, *> && inhoud.keys.all { it is String }) {
                        @Suppress("UNCHECKED_CAST")
                        controleerEigenschappen(knoop, inhoud as Map<String, Any?>, pad, bestand, violations, verplicht)
                        true
                    } else {
                        inhoud == null
                    }
                }

                is SchemaKnoop.Lijst -> {
                    if (inhoud is List<*>) {
                        inhoud.forEach { controleerKnoop(knoop.items, it, "$pad[]", bestand, violations, verplicht) }
                        true
                    } else {
                        inhoud == null
                    }
                }

                is SchemaKnoop.Tekst -> {
                    inhoud == null || inhoud is String
                }

                SchemaKnoop.Duur -> {
                    inhoud == null || inhoud is String
                }

                is SchemaKnoop.Sleutelmap -> {
                    if (inhoud is Map<*, *> && inhoud.keys.all { it is String }) {
                        inhoud.forEach { (sleutel, waarde) -> controleerKnoop(knoop.waarde, waarde, "$pad.$sleutel", bestand, violations, verplicht) }
                        true
                    } else {
                        inhoud == null
                    }
                }

                SchemaKnoop.Booleaans -> {
                    inhoud == null || inhoud is Boolean
                }

                SchemaKnoop.Geheel -> {
                    inhoud == null || inhoud is Int || inhoud is Long || inhoud is BigInteger
                }
            }
        if (!goed) {
            violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, bestand, pad, "Een waarde heeft niet de verwachte vorm")
        } else if (knoop is SchemaKnoop.Tekst && knoop.geheimVerwijzing && inhoud is String && !inhoud.matches(GEHEIMVERWIJZING)) {
            violations += TenantFileViolation(Code.GEHEIM_NIET_ALS_VERWIJZING, bestand, pad, "Een geheim staat nooit in het tenantbestand: geef de naam van het geheim in de secret store op")
        } else if (knoop is SchemaKnoop.Tekst && knoop.opsomming != null && inhoud is String && inhoud !in knoop.opsomming) {
            violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, bestand, pad, "Geen toegestane waarde (toegestaan: ${knoop.opsomming.joinToString()})")
        } else if (knoop is SchemaKnoop.Tekst && knoop.opsomming == null && inhoud is String && inhoud.isBlank()) {
            violations += TenantFileViolation(Code.ONGELDIGE_WAARDE, bestand, pad, "Een lege waarde is niet toegestaan")
        }
    }

    /** Weigert in de gedeelde basis elke eigenschap die bij één tenant hoort: een [GeheimVerwijzing] of een [NietErfbaar] eigenschap (story 11). */
    private fun controleerNietErfbaar(
        knoop: SchemaKnoop,
        waarde: Any?,
        pad: String,
        bestand: String,
        violations: MutableList<TenantFileViolation>,
    ) {
        when (knoop) {
            is SchemaKnoop.Object -> {
                (waarde as? Map<*, *>)?.forEach { (sleutel, inhoud) ->
                    val eigenschap = knoop.eigenschappen[sleutel as? String] ?: return@forEach
                    val eigenschapPad = if (pad.isEmpty()) "$sleutel" else "$pad.$sleutel"
                    if (eigenschap.nietErfbaar && inhoud != null) {
                        violations += TenantFileViolation(Code.NIET_ERFBARE_WAARDE_IN_BASIS, bestand, eigenschapPad, "Dit hoort bij één tenant en kan niet uit de gedeelde basis komen")
                    } else {
                        controleerNietErfbaar(eigenschap.knoop, inhoud, eigenschapPad, bestand, violations)
                    }
                }
            }

            is SchemaKnoop.Sleutelmap -> {
                (waarde as? Map<*, *>)?.forEach { (sleutel, inhoud) -> controleerNietErfbaar(knoop.waarde, inhoud, "$pad.$sleutel", bestand, violations) }
            }

            is SchemaKnoop.Lijst -> {
                (waarde as? List<*>)?.forEach { controleerNietErfbaar(knoop.items, it, "$pad[]", bestand, violations) }
            }

            // Een verwijzing als item van een lijst of waarde van een map heeft geen eigenschap die niet-erfbaar kan zijn: de knoop zelf telt.
            is SchemaKnoop.Tekst -> {
                if (knoop.geheimVerwijzing && waarde != null) {
                    violations += TenantFileViolation(Code.NIET_ERFBARE_WAARDE_IN_BASIS, bestand, pad, "Dit hoort bij één tenant en kan niet uit de gedeelde basis komen")
                }
            }

            else -> {}
        }
    }

    private fun voegSamen(
        basis: Map<String, Any?>,
        eigen: Map<String, Any?>,
    ): Map<String, Any?> {
        val resultaat = LinkedHashMap(basis)
        eigen.forEach { (sleutel, waarde) ->
            val bestaand = resultaat[sleutel]
            @Suppress("UNCHECKED_CAST")
            resultaat[sleutel] =
                if (bestaand is Map<*, *> && waarde is Map<*, *>) {
                    voegSamen(bestaand as Map<String, Any?>, waarde as Map<String, Any?>)
                } else {
                    waarde
                }
        }
        return resultaat
    }

    private class Geladen(
        val bestand: String,
        val definition: TenantDefinition,
    )

    companion object {
        /** Wordt `true` zodra de laatste module naar een tenantbestand is gemigreerd (epic #499, stap 7). */
        const val MEERDERE_TENANTS_TOEGESTAAN = false

        private const val BASIS = "base"
        private const val MAX_BESTAND = 64 * 1024

        /** Eigenschappen die bij precies één tenant horen en dus nooit uit de gedeelde basis komen; credentials en sleutels volgen uit [GeheimVerwijzing] en [NietErfbaar]. */
        private val NIET_ERFBAAR = listOf("oin", "naam", "domeinen")

        private val GEHEIMVERWIJZING = Regex(GeheimVerwijzing.PATROON)
        private val HOSTNAAM = Regex(TenantSchemaModel.HOSTNAAM_PATROON)

        private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    }
}