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
package nl.nlportal.commonground.authentication

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet.Builder
import com.nimbusds.jwt.SignedJWT
import nl.nlportal.commonground.authentication.IdentiteitUitToken.BSN_CLAIM
import nl.nlportal.commonground.authentication.IdentiteitUitToken.KVK_CLAIM
import nl.nlportal.commonground.authentication.IdentiteitUitToken.MACHTIGINGSDIENST_CLAIM
import nl.nlportal.commonground.authentication.IdentiteitUitToken.VERTEGENWOORDIGDE_CLAIM
import nl.nlportal.commonground.authentication.IdentiteitUitToken.VESTIGINGSNUMMER_CLAIM
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import org.springframework.security.oauth2.jwt.Jwt
import java.util.Date

/** Bouwt tokens zoals NL Portal ze na de token exchange ontvangt, en de authenticatie die daaruit volgt. */
class JwtBuilder {
    private var vertegenwoordigdeBsn: String? = null
    private var vertegenwoordigdeKvk: String? = null

    private var jwtBuilder: Jwt.Builder =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .issuer(ISSUER)
            .subject(SUBJECT)

    fun issuer(issuer: String): JwtBuilder {
        jwtBuilder.issuer(issuer)
        return this
    }

    fun subject(subject: String): JwtBuilder {
        jwtBuilder.subject(subject)
        return this
    }

    fun vertegenwoordigdeBsn(bsn: String): JwtBuilder {
        assert(vertegenwoordigdeKvk == null) { "cannot set bsn for jwt that already has kvk" }

        jwtBuilder.claim(VERTEGENWOORDIGDE_CLAIM, mapOf<String, Any>(BSN_CLAIM to bsn))
        this.vertegenwoordigdeBsn = bsn

        return this
    }

    fun vertegenwoordigdeKvk(kvk: String): JwtBuilder {
        assert(vertegenwoordigdeBsn == null) { "cannot set kvk for jwt that already has bsn" }

        jwtBuilder.claim(VERTEGENWOORDIGDE_CLAIM, mapOf<String, Any>(KVK_CLAIM to kvk))
        this.vertegenwoordigdeKvk = kvk

        return this
    }

    fun vestigingsNummerKvk(
        kvk: String,
        vestigingsNummer: String,
    ): JwtBuilder {
        jwtBuilder.claim(
            VERTEGENWOORDIGDE_CLAIM,
            mapOf<String, Any>(KVK_CLAIM to kvk, VESTIGINGSNUMMER_CLAIM to vestigingsNummer),
        )
        this.vertegenwoordigdeKvk = kvk

        return this
    }

    fun machtingDienstKvk(machtingDienst: String): JwtBuilder {
        jwtBuilder.claim(MACHTIGINGSDIENST_CLAIM, listOf(machtingDienst))

        return this
    }

    fun buildJwt(): Jwt {
        if (this.vertegenwoordigdeBsn == null && this.vertegenwoordigdeKvk == null) {
            throw IllegalStateException("vertegenwoordigde needs to be set with either bsn or kvk")
        }

        return jwtBuilder.build()
    }

    fun buildJwtString(secretString: String): String {
        val claimsSet =
            Builder()
                .issueTime(Date())
                .expirationTime(Date(System.currentTimeMillis() + 20000)) // 15 minutes
                .claim("claim", "value")
                .build()

        val signedJWT =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.HS256)
                    .build(),
                claimsSet,
            )
        signedJWT.sign(MACSigner(secretString.encodeToByteArray()))
        return signedJWT.serialize()
    }

    /** De authenticatie via dezelfde leesroute als in productie. */
    fun buildAuthentication(): PortalAuthentication {
        val jwt = buildJwt()
        return PortalAuthentication(IdentiteitUitToken.lees(jwt), jwt.tokenValue, emptyList())
    }

    companion object {
        const val ISSUER = "https://login.example.nl/realms/portal"
        const val SUBJECT = "c0ffee00-0000-4000-8000-000000000001"
    }
}