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
package nl.nlportal.core.modulegate

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.Flag
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.nlportal.core.featureflag.FeatureContextFactory
import nl.nlportal.core.featureflag.FeatureFlag
import nl.nlportal.core.featureflag.FeatureFlagProperties
import nl.nlportal.core.featureflag.FeatureGate
import nl.nlportal.core.featureflag.OpenFeatureFeatureGate
import nl.nlportal.core.tenancy.Authenticatie
import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.Omgevingstype
import nl.nlportal.core.tenancy.Sessie
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantContextMissingException
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantKoppeling
import nl.nlportal.core.tenancy.TenantKoppelingAuth
import nl.nlportal.core.tenancy.TenantKoppelingAuthType
import nl.nlportal.core.tenancy.Thema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import reactor.util.context.Context

/**
 * Seam: de centrale modulegate per tenant. Bruikbaar is configuratie aanwezig EN permission-flag aan voor de OIN van de tenant
 * (met `requires`), fail-closed bij een ontbrekende flag of een flagfout; een flagwijziging vooraf maakt een module zonder
 * configuratie nooit bruikbaar.
 */
class ModuleGateTenantTest {
    private val bosrand = Oin("00000099000000000001")
    private val wijdeVeld = Oin("00000099000000000002")

    private val documenten = ModuleFlag("documentenapis", configuratieNaam = "documenten-api")
    private val berichten = ModuleFlag("berichten", requires = listOf(documenten))
    private val taak = ModuleFlag("taak")

    private val koppeling = TenantKoppeling("https://bron.example", TenantKoppelingAuth(TenantKoppelingAuthType.GEEN))

    private fun tenant(
        oin: Oin,
        modules: Set<String> = emptySet(),
        koppelingen: Set<String> = emptySet(),
    ) = TenantDefinition(
        oin = oin,
        naam = "Gemeente $oin",
        omgevingstype = Omgevingstype.TEST,
        domeinen = listOf("$oin.example"),
        thema = Thema(titel = "t"),
        inlogmiddelen = setOf(Inlogmiddel.TEST),
        authenticatie = Authenticatie("https://login.example/realms/$oin", "/k.jwk"),
        minimumNiveau = Betrouwbaarheidsniveau.SUBSTANTIEEL,
        sessie = Sessie(),
        koppelingen = koppelingen.associateWith { koppeling },
        modules = modules.associateWith { "config" },
    )

    /** Per flag de OIN's waarvoor hij aan staat; een flag die er niet staat is niet gedefinieerd. */
    private fun provider(aanVoor: Map<String, Set<Oin>>): InMemoryProvider =
        InMemoryProvider(
            aanVoor.mapValues { (_, oins) ->
                Flag
                    .builder<Boolean>()
                    .variant("on", true)
                    .variant("off", false)
                    .defaultVariant("off")
                    .contextEvaluator { _, context -> context.getValue("tenant")?.asString() in oins.map { it.value } }
                    .build()
            },
        )

    private fun gate(
        aanVoor: Map<String, Set<Oin>>,
        vararg tenants: TenantDefinition,
        directory: Boolean = true,
        modules: List<ModuleFlag> = listOf(documenten, berichten, taak),
    ): ModuleGate {
        val api = OpenFeatureAPI.getInstance()
        api.setProviderAndWait(provider(aanVoor))
        return ModuleGate(
            featureGate = OpenFeatureFeatureGate(api.client),
            featureContextFactory = FeatureContextFactory(FeatureFlagProperties.Context()),
            modules = modules,
            tenants = if (directory) TenantDirectory(tenants.toList()) else null,
        )
    }

    private fun alleen(
        flag: ModuleFlag,
        oins: List<Oin>,
    ) = flag.flag.key to oins.toSet()

    @Test
    fun `de moduleflag is een permission-flag die uit staat zonder definitie`() {
        assertEquals("permission.taak.enabled", taak.flag.key)
        assertEquals(nl.nlportal.core.featureflag.ToggleType.PERMISSION, taak.flag.type)
        assertFalse(taak.flag.default)
    }

    @Test
    fun `een module is bruikbaar met configuratie en een flag die voor de tenant aan staat`() {
        val gate = gate(mapOf(alleen(taak, listOf(bosrand))), tenant(bosrand, modules = setOf("taak")))

        assertEquals(ModuleBeschikbaarheid.BRUIKBAAR, gate.beschikbaarheid(bosrand, taak))
        assertTrue(gate.isUsable(bosrand, taak))
    }

    @Test
    fun `een koppeling zonder modulegegevens telt als configuratie`() {
        val gate = gate(mapOf(alleen(taak, listOf(bosrand))), tenant(bosrand, koppelingen = setOf("taak")))

        assertTrue(gate.isUsable(bosrand, taak))
    }

    @Test
    fun `de flag wordt per tenant geevalueerd met de OIN in de context`() {
        val gate =
            gate(
                mapOf(alleen(taak, listOf(bosrand))),
                tenant(bosrand, modules = setOf("taak")),
                tenant(wijdeVeld, modules = setOf("taak")),
            )

        assertEquals(ModuleBeschikbaarheid.BRUIKBAAR, gate.beschikbaarheid(bosrand, taak))
        assertEquals(ModuleBeschikbaarheid.FLAG_UIT, gate.beschikbaarheid(wijdeVeld, taak))
    }

    @Test
    fun `een ontbrekende flag laat de module uit staan`() {
        val gate = gate(emptyMap(), tenant(bosrand, modules = setOf("taak")))

        assertEquals(ModuleBeschikbaarheid.FLAG_UIT, gate.beschikbaarheid(bosrand, taak))
    }

    @Test
    fun `een flagfout laat de module uit staan en lekt de fout niet`() {
        val kapot =
            object : FeatureGate {
                override fun isEnabled(
                    flag: FeatureFlag,
                    context: EvaluationContext,
                ): Boolean = error("provider onbereikbaar BSN 999990001")

                override fun booleanMap(
                    key: String,
                    context: EvaluationContext,
                ) = emptyMap<String, Boolean>()
            }
        val gate = ModuleGate(kapot, FeatureContextFactory(FeatureFlagProperties.Context()), listOf(taak), TenantDirectory(listOf(tenant(bosrand, modules = setOf("taak")))))

        assertEquals(ModuleBeschikbaarheid.FLAG_UIT, gate.beschikbaarheid(bosrand, taak))
        assertFalse(gate.isEnabled(taak, FeatureContextFactory(FeatureFlagProperties.Context()).create(null)))
    }

    @Test
    fun `een flag die aan staat voor een module zonder configuratie maakt hem niet bruikbaar, ook niet na een flagwijziging voor de restart`() {
        val voor = gate(emptyMap(), tenant(bosrand))
        assertEquals(ModuleBeschikbaarheid.NIET_GECONFIGUREERD, voor.beschikbaarheid(bosrand, taak))

        // De flagd-provider pollt het bestand: de flag gaat aan terwijl het tenantbestand (en dus de configuratie) hetzelfde blijft.
        OpenFeatureAPI.getInstance().setProviderAndWait(provider(mapOf(alleen(taak, listOf(bosrand, wijdeVeld)))))

        assertEquals(ModuleBeschikbaarheid.NIET_GECONFIGUREERD, voor.beschikbaarheid(bosrand, taak))
        assertFalse(voor.isUsable(bosrand, taak))
        assertThrows<ModuleNotAvailableException> { runBlocking { voor.requireUsable(taak, tenant = bosrand) } }
    }

    @Test
    fun `een flagwijziging voor de ene tenant maakt de module van een andere tenant niet bruikbaar`() {
        val gate = gate(emptyMap(), tenant(bosrand, modules = setOf("taak")), tenant(wijdeVeld, modules = setOf("taak")))

        OpenFeatureAPI.getInstance().setProviderAndWait(provider(mapOf(alleen(taak, listOf(bosrand)))))

        assertTrue(gate.isUsable(bosrand, taak))
        assertFalse(gate.isUsable(wijdeVeld, taak))
    }

    @Test
    fun `een vereiste module moet zelf bruikbaar zijn, ook zijn eigen configuratie`() {
        val beide = mapOf(alleen(berichten, listOf(bosrand)), alleen(documenten, listOf(bosrand)))

        val compleet = gate(beide, tenant(bosrand, modules = setOf("berichten", "documenten-api")))
        assertEquals(ModuleBeschikbaarheid.BRUIKBAAR, compleet.beschikbaarheid(bosrand, berichten))

        val zonderConfiguratie = gate(beide, tenant(bosrand, modules = setOf("berichten")))
        assertEquals(ModuleBeschikbaarheid.VEREISTE_NIET_BRUIKBAAR, zonderConfiguratie.beschikbaarheid(bosrand, berichten))

        val vereisteFlagUit = gate(mapOf(alleen(berichten, listOf(bosrand))), tenant(bosrand, modules = setOf("berichten", "documenten-api")))
        assertEquals(ModuleBeschikbaarheid.VEREISTE_NIET_BRUIKBAAR, vereisteFlagUit.beschikbaarheid(bosrand, berichten))
    }

    @Test
    fun `een onbekende tenant of een runtime zonder directory is niet bruikbaar`() {
        val metDirectory = gate(mapOf(alleen(taak, listOf(bosrand, wijdeVeld))), tenant(bosrand, modules = setOf("taak")))
        assertEquals(ModuleBeschikbaarheid.TENANT_ONBEKEND, metDirectory.beschikbaarheid(wijdeVeld, taak))

        val zonder = gate(mapOf(alleen(taak, listOf(bosrand))), directory = false)
        assertEquals(ModuleBeschikbaarheid.TENANT_ONBEKEND, zonder.beschikbaarheid(bosrand, taak))
    }

    @Test
    fun `de status noemt elke geregistreerde module voor de tenant`() {
        val gate = gate(mapOf(alleen(taak, listOf(bosrand))), tenant(bosrand, modules = setOf("taak")))

        assertEquals(
            mapOf(
                "documentenapis" to ModuleBeschikbaarheid.NIET_GECONFIGUREERD,
                "berichten" to ModuleBeschikbaarheid.NIET_GECONFIGUREERD,
                "taak" to ModuleBeschikbaarheid.BRUIKBAAR,
            ),
            gate.status(bosrand),
        )
    }

    @Test
    fun `de suspend-variant leest de tenant uit de TenantContext`() {
        val gate = gate(mapOf(alleen(taak, listOf(bosrand))), tenant(bosrand, modules = setOf("taak")), tenant(wijdeVeld, modules = setOf("taak")))

        runBlocking {
            metTenant(bosrand) {
                assertTrue(gate.isUsable(taak))
                gate.requireUsable(taak)
            }
            metTenant(wijdeVeld) {
                assertFalse(gate.isUsable(taak))
                val fout = assertThrows<ModuleNotAvailableException> { gate.requireUsable(taak) }
                assertEquals("taak", fout.module)
            }
        }
    }

    @Test
    fun `zonder tenant in de context weigert de gate met een serverfout, nooit met een standaardtenant`() {
        val gate = gate(mapOf(alleen(taak, listOf(bosrand))), tenant(bosrand, modules = setOf("taak")))

        assertThrows<TenantContextMissingException> { runBlocking { gate.isUsable(taak) } }
        assertThrows<TenantContextMissingException> { runBlocking { gate.requireUsable(taak) } }
    }

    @Test
    fun `de legacy-gate op een context blijft werken tot de centrale afdwinging hem vervangt`() {
        val gate = gate(mapOf(alleen(taak, listOf(bosrand))), tenant(bosrand, modules = setOf("taak")), modules = listOf(taak))
        val context = FeatureContextFactory(FeatureFlagProperties.Context().apply { tenant = bosrand.value }).create(null)

        assertTrue(gate.isEnabled(taak, context))
        assertEquals(mapOf("taak" to true), gate.status(context))
    }

    private suspend fun metTenant(
        oin: Oin,
        blok: suspend () -> Unit,
    ) = withContext(ReactorContext(Context.of(TenantContext.SLEUTEL, oin))) { blok() }
}