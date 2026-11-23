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
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Waarom een clientsleutel niet bruikbaar is. De melding noemt nooit een pad of sleuteldeel; de aanroeper voegt veld en OIN toe. */
class ClientSleutelFout(
    val soort: Soort,
    message: String,
) : RuntimeException(message) {
    enum class Soort { ONTBREEKT, ONGELDIG, BUITEN_NAAMRUIMTE }
}

/**
 * Het sleutelbestand van de client-registratie (`authenticatie.sleutelBestand`) van een tenant: de bestandsmount van de secret
 * store onder de vaste sleutelmap (`nl-portal.sleutelmap`), per tenant in een map met de OIN als naam. Eén bron voor de
 * opstartbouw van de runtime, de loader en de validator-CLI.
 *
 * De eisen aan de sleutel (NL GOV, ASVS 11.2.3): privé RSA van minstens [MIN_BITS] bit met `alg: PS256` en een `kid`.
 */
object ClientSleutelBestand {
    const val ALGORITME = "PS256"
    const val MIN_BITS = 3072

    /**
     * Het pad van [sleutelBestand] van tenant [oin]: een relatief pad is relatief aan [sleutelmap]. Het echte pad (symlinks opgelost)
     * ligt onder `<sleutelmap>/<oin>/`; een symlink naar de map van een andere tenant, of een pad dat via `..` ontsnapt, is geweigerd.
     * Een bestand dat niet bestaat is [ClientSleutelFout.Soort.ONTBREEKT].
     */
    fun pad(
        sleutelBestand: String,
        oin: String,
        sleutelmap: Path,
    ): Path {
        val kandidaat = Path.of(sleutelBestand).let { if (it.isAbsolute) it else sleutelmap.resolve(it) }.normalize()
        val echt =
            try {
                kandidaat.toRealPath()
            } catch (e: IOException) {
                throw ClientSleutelFout(ClientSleutelFout.Soort.ONTBREEKT, "het sleutelbestand ontbreekt of is niet te lezen")
            }
        val eigenMap =
            try {
                sleutelmap.resolve(oin).toRealPath()
            } catch (e: IOException) {
                throw ClientSleutelFout(ClientSleutelFout.Soort.ONTBREEKT, "de map van de tenant ontbreekt onder de sleutelmap")
            }
        if (!echt.startsWith(eigenMap)) {
            throw ClientSleutelFout(ClientSleutelFout.Soort.BUITEN_NAAMRUIMTE, "het sleutelbestand ligt niet in de map van deze tenant onder de sleutelmap")
        }
        if (!Files.isRegularFile(echt)) throw ClientSleutelFout(ClientSleutelFout.Soort.ONTBREEKT, "het sleutelbestand is geen bestand")
        return echt
    }

    /** Leest en toetst de sleutel op [pad]; een fout bevat nooit sleuteldelen. */
    fun lees(pad: Path): RSAKey {
        val jwk =
            try {
                JWK.parse(Files.readString(pad))
            } catch (e: java.text.ParseException) {
                throw ongeldig("het sleutelbestand is geen geldige JWK")
            } catch (e: IOException) {
                throw ClientSleutelFout(ClientSleutelFout.Soort.ONTBREEKT, "het sleutelbestand is niet te lezen")
            }
        if (jwk !is RSAKey) throw ongeldig("de clientsleutel is geen RSA-sleutel")
        if (!jwk.isPrivate) throw ongeldig("de clientsleutel is niet privé; private_key_jwt vraagt het private deel")
        if (jwk.keyID.isNullOrBlank()) throw ongeldig("de clientsleutel mist een kid (keyId)")
        if (jwk.algorithm != JWSAlgorithm.PS256) throw ongeldig("de clientsleutel moet alg $ALGORITME dragen (zonder alg valt de client stil terug op RS256)")
        if (jwk.size() < MIN_BITS) throw ongeldig("de clientsleutel moet minstens $MIN_BITS bit zijn")
        return jwk
    }

    private fun ongeldig(bericht: String) = ClientSleutelFout(ClientSleutelFout.Soort.ONGELDIG, bericht)
}