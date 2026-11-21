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

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Bouwt de gesigneerde `client_assertion` van OAuth2 `private_key_jwt` (RFC 7523) uit een PKCS#8 PEM-sleutelbestand. */
internal object ClientAssertion {
    const val TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
    private const val GELDIGHEID_MILLIS = 60_000L

    fun sign(
        clientId: String,
        tokenUrl: String,
        sleutel: RSAPrivateKey,
    ): String {
        val nu = System.currentTimeMillis()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(clientId)
                .subject(clientId)
                .audience(tokenUrl)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date(nu))
                .expirationTime(Date(nu + GELDIGHEID_MILLIS))
                .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
        jwt.sign(RSASSASigner(sleutel))
        return jwt.serialize()
    }

    /**
     * Leest de sleutel bij het opstarten, zodat een onleesbaar bestand meteen faalt en niet bij de eerste tokenaanvraag.
     * De reden noemt het pad niet en de oorzaak ook niet: een parserfout kan sleutelmateriaal bevatten.
     */
    fun leesSleutel(bestand: String): RSAPrivateKey =
        try {
            val pem =
                Files
                    .readString(Path.of(bestand))
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace(Regex("\\s"), "")
            KeyFactory
                .getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))) as RSAPrivateKey
        } catch (e: Exception) {
            throw IllegalArgumentException("Het sleutelbestand van de koppeling is niet te lezen als PKCS#8 RSA-sleutel")
        }
}
