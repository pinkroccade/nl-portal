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
package nl.nlportal.architecture.fixtures.identityvio.feature

import com.nimbusds.jwt.SignedJWT
import nl.nlportal.architecture.fixtures.identityvio.auth.Identity
import nl.nlportal.architecture.fixtures.identityvio.auth.Session
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class BuildsIdentity {
    fun forge() = Identity("forged")
}

class BuildsIdentityByReference {
    fun factory(): (String) -> Identity = ::Identity
}

class CopiesIdentity {
    fun other(identity: Identity) = identity.copy(subject = "other")
}

class ReadsClaims {
    fun subjectOf(jwt: Jwt): String? = jwt.subject
}

class ReadsTokenAttributes {
    fun bsnOf(authentication: JwtAuthenticationToken): Any? = authentication.tokenAttributes["bsn"]
}

class ParsesBearerToken {
    fun claimsOf(session: Session) = SignedJWT.parse(session.bearerToken).jwtClaimsSet
}

class ReadsClaimsByReference {
    fun reader(): (Jwt) -> Map<String, Any> = Jwt::getClaims
}

class ReadsBearerToken {
    fun tokenOf(session: Session): String = session.bearerToken
}
