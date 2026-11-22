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

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import nl.nlportal.core.tenancy.TenantFileViolation.Code
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Seam: het sleutelbestand van de client-registratie (`authenticatie.sleutelBestand`, story 14) zoals een beheerder het aanlevert en
 * zoals de loader en de validator-CLI het weigeren: een pad buiten de naamruimte van de eigen OIN (ook via een symlink), een ontbrekend of
 * onbruikbaar bestand, een dubbele `kid` of sleutel. Elke melding noemt veld en OIN, nooit het pad of een sleuteldeel.
 */
class TenantSleutelbestandTest {
    @TempDir
    lateinit var dir: Path

    private val a = "00000099000000000001"
    private val b = "00000099000000000002"
    private val sleutelmap get() = dir.resolve("sleutels")
    private val tenants get() = dir.resolve("tenants")

    private fun tenantBestand(
        oin: String,
        sleutelBestand: String,
    ) {
        Files.createDirectories(tenants)
        Files.writeString(
            tenants.resolve("$oin.yaml"),
            "oin: \"$oin\"\nnaam: Gemeente\ndomeinen: [h$oin.example]\nthema:\n  titel: T\nomgevingstype: test\ninlogmiddelen: [test]\n" +
                "authenticatie:\n  issuer: https://login.example/realms/$oin\n  sleutelBestand: \"$sleutelBestand\"\n",
        )
    }

    private fun sleutelBestand(
        oin: String,
        naam: String,
        inhoud: String,
    ): Path =
        sleutelmap
            .resolve(oin)
            .resolve(naam)
            .also { Files.createDirectories(it.parent) }
            .also { Files.writeString(it, inhoud) }

    private fun laad(
        meerdere: Boolean = false,
        metSleutelmap: Boolean = true,
    ) = TenantFileLoader(meerdereTenantsToegestaan = meerdere, sleutelmap = sleutelmap.takeIf { metSleutelmap }).load(tenants)

    private fun codes(result: TenantFileResult) = result.violations.map { it.code }

    @Test
    fun `een sleutel in de eigen map onder de sleutelmap wordt geladen, relatief en absoluut`() {
        sleutelBestand(a, "k.jwk", sleutel("ka").toJSONString())
        tenantBestand(a, "$a/k.jwk")
        assertThat(laad().violations).isEmpty()

        tenantBestand(
            a,
            sleutelmap
                .resolve(a)
                .resolve("k.jwk")
                .toString()
                .replace('\\', '/'),
        )
        assertThat(laad().violations).isEmpty()
    }

    @Test
    fun `een pad buiten de naamruimte van de eigen OIN wordt geweigerd met veld en OIN maar zonder het pad`() {
        listOf(
            "/run/secrets/$b/k.jwk",
            "$a/../$b/k.jwk",
            "../$a/k.jwk",
            "/run/secrets/k.jwk",
            "/run/secrets/$a",
            "/run/$a/$b/k.jwk",
            "/run/$a/$a/k.jwk",
        ).forEach { pad ->
            tenantBestand(a, pad)

            val violation = laad(metSleutelmap = false).violations.single()

            assertThat(violation.code).describedAs(pad).isEqualTo(Code.GEHEIM_VAN_ANDERE_TENANT)
            assertThat(violation.pad).isEqualTo("authenticatie.sleutelBestand")
            assertThat(violation.bericht).contains("Tenant $a")
            assertThat(violation.toString()).doesNotContain("k.jwk").doesNotContain("/run/")
        }
    }

    @Test
    fun `een ontbrekend sleutelbestand weigert het opstarten, maar zonder sleutelmap wordt alleen het pad getoetst`() {
        tenantBestand(a, "$a/ontbreekt.jwk")

        val violation = laad().violations.single()

        assertThat(violation.code).isEqualTo(Code.SLEUTELBESTAND_ONTBREEKT)
        assertThat(violation.pad).isEqualTo("authenticatie.sleutelBestand")
        assertThat(violation.bericht).startsWith("Tenant $a:")
        assertThat(violation.toString()).doesNotContain("ontbreekt.jwk")
        assertThat(laad(metSleutelmap = false).violations).isEmpty()
    }

    @Test
    fun `een onbruikbare clientsleutel wordt geweigerd en de melding bevat geen sleuteldeel`() {
        val ec = ECKeyGenerator(Curve.P_256).keyID("e").generate().toJSONString()
        val goed = sleutel("kid-1")
        val gevallen =
            mapOf(
                "geen json" to "geen geldige JWK",
                sleutel("kid-1").toPublicJWK().toJSONString() to "niet privé",
                sleutel(kid = null).toJSONString() to "mist een kid",
                sleutel(kid = " ").toJSONString() to "mist een kid",
                sleutel(alg = null).toJSONString() to "PS256",
                sleutel(alg = JWSAlgorithm.RS256).toJSONString() to "PS256",
                sleutel(bits = 2048).toJSONString() to "3072",
                ec to "geen RSA",
            )
        tenantBestand(a, "$a/k.jwk")
        gevallen.forEach { (inhoud, verwacht) ->
            sleutelBestand(a, "k.jwk", inhoud)

            val violation = laad().violations.single()

            assertThat(violation.code).describedAs(verwacht).isEqualTo(Code.SLEUTELBESTAND_ONGELDIG)
            assertThat(violation.bericht).contains(verwacht).startsWith("Tenant $a:")
            assertThat(violation.toString()).doesNotContain("k.jwk").doesNotContain(goed.modulus.toString().take(20))
        }
    }

    @Test
    fun `een symlink naar de map van een andere tenant wordt geweigerd`() {
        sleutelBestand(b, "k.jwk", sleutel("kb").toJSONString())
        val link = sleutelmap.resolve(a).resolve("link.jwk")
        Files.createDirectories(link.parent)
        val kanLinken = runCatching { Files.createSymbolicLink(link, sleutelmap.resolve(b).resolve("k.jwk")) }.isSuccess
        assumeTrue(kanLinken, "dit besturingssysteem laat hier geen symlink toe; de omweg via .. staat in het volgende geval")
        tenantBestand(a, "$a/link.jwk")

        val violation = laad().violations.single()

        assertThat(violation.code).isEqualTo(Code.GEHEIM_VAN_ANDERE_TENANT)
        assertThat(violation.bericht).contains("Tenant $a").contains("niet in de map van deze tenant")
        assertThat(violation.toString()).doesNotContain("link.jwk")
    }

    @Test
    fun `een map met de OIN van de tenant buiten de sleutelmap valt lexicaal goed maar wordt als echt pad geweigerd`() {
        val elders = dir.resolve("elders").resolve(a)
        Files.createDirectories(elders)
        Files.writeString(elders.resolve("k.jwk"), sleutel("ka").toJSONString())
        Files.createDirectories(sleutelmap.resolve(a))
        tenantBestand(a, elders.resolve("k.jwk").invariantSeparatorsPathString)

        val violation = laad().violations.single()

        assertThat(violation.code).isEqualTo(Code.GEHEIM_VAN_ANDERE_TENANT)
        assertThat(violation.bericht).contains("Tenant $a").contains("niet in de map van deze tenant")
        assertThat(violation.toString()).doesNotContain("elders").doesNotContain("k.jwk")
        assertThat(laad(metSleutelmap = false).violations).isEmpty()
    }

    @Test
    fun `een pad dat via twee mappen naar de sleutel van een andere tenant ontsnapt wordt geweigerd`() {
        sleutelBestand(b, "k.jwk", sleutel("kb").toJSONString())
        Files.createDirectories(sleutelmap.resolve(a))

        // dit pad slaagt lexicaal niet (..); de echte-padcontrole vangt hetzelfde als iets het toch zou doorlaten
        assertThatThrownBy { ClientSleutelBestand.pad("../sleutels/$b/k.jwk", a, sleutelmap) }
            .isInstanceOfSatisfying(ClientSleutelFout::class.java) { assertThat(it.soort).isEqualTo(ClientSleutelFout.Soort.BUITEN_NAAMRUIMTE) }
        assertThatThrownBy { ClientSleutelBestand.pad(sleutelmap.resolve(b).resolve("k.jwk").toString(), a, sleutelmap) }
            .isInstanceOfSatisfying(ClientSleutelFout::class.java) { assertThat(it.soort).isEqualTo(ClientSleutelFout.Soort.BUITEN_NAAMRUIMTE) }
    }

    @Test
    fun `een map in plaats van een bestand, een ontbrekende tenantmap en een onleesbaar bestand zijn ontbrekend`() {
        Files.createDirectories(sleutelmap.resolve(a).resolve("map.jwk"))

        listOf(
            Triple("$a/map.jwk", a, "geen bestand"),
            Triple("$b/k.jwk", b, "ontbreekt"),
        ).forEach { (pad, oin, bevat) ->
            assertThatThrownBy { ClientSleutelBestand.pad(pad, oin, sleutelmap) }
                .isInstanceOfSatisfying(ClientSleutelFout::class.java) {
                    assertThat(it.soort).isEqualTo(ClientSleutelFout.Soort.ONTBREEKT)
                    assertThat(it.message).contains(bevat)
                }
        }
        assertThatThrownBy { ClientSleutelBestand.lees(sleutelmap.resolve(a).resolve("map.jwk")) }
            .isInstanceOfSatisfying(ClientSleutelFout::class.java) {
                assertThat(it.soort).isEqualTo(ClientSleutelFout.Soort.ONTBREEKT)
                assertThat(it.message).contains("niet te lezen")
            }
        // een tenantmap die er wel is, met het bestand erin, maar de OIN-map van de tenant zelf ontbreekt: ook ontbrekend
        Files.writeString(sleutelmap.resolve("los.jwk"), "x")
        assertThatThrownBy { ClientSleutelBestand.pad("los.jwk", b, sleutelmap) }
            .isInstanceOfSatisfying(ClientSleutelFout::class.java) { assertThat(it.message).contains("map van de tenant ontbreekt") }
    }

    @Test
    fun `twee tenants met dezelfde keyId of dezelfde sleutel worden geweigerd`() {
        val gedeeld = sleutel("zelfde")
        sleutelBestand(a, "k.jwk", gedeeld.toJSONString())
        sleutelBestand(b, "k.jwk", sleutel("zelfde").toJSONString())
        tenantBestand(a, "$a/k.jwk")
        tenantBestand(b, "$b/k.jwk")

        val zelfdeKid = laad(meerdere = true).violations.single()

        assertThat(zelfdeKid.code).isEqualTo(Code.DUBBELE_KEYID)
        assertThat(zelfdeKid.bericht).contains("Tenant $b").contains("$a.yaml")
        assertThat(zelfdeKid.pad).isEqualTo("authenticatie.sleutelBestand")

        sleutelBestand(
            b,
            "k.jwk",
            RSAKey
                .Builder(gedeeld.toRSAPublicKey())
                .privateKey(gedeeld.toRSAPrivateKey())
                .keyID("andere-kid")
                .algorithm(JWSAlgorithm.PS256)
                .build()
                .toJSONString(),
        )

        val zelfdeSleutel = laad(meerdere = true).violations.single()

        assertThat(zelfdeSleutel.code).isEqualTo(Code.DUBBELE_CLIENTSLEUTEL)
        assertThat(zelfdeSleutel.bericht).contains("Tenant $b")
    }

    @Test
    fun `de validator-CLI controleert met sleutelmap dat het sleutelbestand bestaat en toetst zonder de optie alleen het pad`() {
        tenantBestand(a, "$a/k.jwk")

        val zonder = Uitvoer()
        assertThat(zonder.run(tenants.toString())).isZero()

        val ontbreekt = Uitvoer()
        assertThat(ontbreekt.run(tenants.toString(), "--sleutelmap=$sleutelmap")).isEqualTo(1)
        assertThat(ontbreekt.stderr)
            .contains("SLEUTELBESTAND_ONTBREEKT")
            .contains("authenticatie.sleutelBestand")
            .contains("Tenant $a")
            .doesNotContain("k.jwk")

        sleutelBestand(a, "k.jwk", sleutel("ka").toJSONString())
        val aanwezig = Uitvoer()
        assertThat(aanwezig.run(tenants.toString(), "--sleutelmap=$sleutelmap")).isZero()
        assertThat(aanwezig.stdout).contains("OK: 1 tenant(s): $a")
    }

    @Test
    fun `de validator-CLI weigert een pad buiten de naamruimte ook zonder sleutelmap`() {
        tenantBestand(a, "/run/secrets/$b/k.jwk")

        val uitvoer = Uitvoer()

        assertThat(uitvoer.run(tenants.toString())).isEqualTo(1)
        assertThat(uitvoer.stderr).contains("GEHEIM_VAN_ANDERE_TENANT").contains("Tenant $a").doesNotContain("k.jwk")
    }

    @Test
    fun `een lege of dubbele optie sleutelmap is verkeerd gebruik`() {
        tenantBestand(a, "$a/k.jwk")

        assertThat(Uitvoer().run(tenants.toString(), "--sleutelmap=")).isEqualTo(2)
        assertThat(Uitvoer().run(tenants.toString(), "--sleutelmap=x", "--sleutelmap=y")).isEqualTo(2)
    }

    private class Uitvoer {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        fun run(vararg args: String) = TenantValidatorCli.run(args.toList(), PrintStream(out), PrintStream(err))

        val stdout get() = out.toString()
        val stderr get() = err.toString()
    }

    private fun sleutel(
        kid: String? = "kid",
        alg: JWSAlgorithm? = JWSAlgorithm.PS256,
        bits: Int = 3072,
    ): RSAKey =
        RSAKeyGenerator(bits)
            .also { g ->
                kid?.let { g.keyID(it) }
                alg?.let { g.algorithm(it) }
            }.generate()
}