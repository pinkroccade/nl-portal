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
package nl.nlportal.commonground.authentication.bff

import com.nimbusds.jose.jwk.RSAKey
import nl.nlportal.core.tenancy.ClientSleutelBestand
import nl.nlportal.core.tenancy.ClientSleutelFout
import nl.nlportal.core.tenancy.Oin
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDefinition
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.file.Path

/**
 * De private clientsleutels van de tenants voor `private_key_jwt`, gelezen uit de bestandsmount van de secret store onder de vaste sleutelmap
 * (`nl-portal.sleutelmap`, per tenant een map met de OIN als naam). Elke tenant heeft een eigen sleutel: RSA van minstens 3072 bit met
 * `alg: PS256` (NL GOV, ASVS 11.2.3) en een `kid`; het echte pad (symlinks opgelost) ligt onder de map van de eigen tenant. Een ontbrekend of
 * onbruikbaar bestand, een pad buiten de eigen map, een dubbele `kid` en dezelfde sleutel bij twee tenants laten de runtime niet starten.
 * Berichten bevatten nooit sleuteldelen of paden.
 */
class TenantClientSleutels private constructor(
    private val perOin: Map<Oin, RSAKey>,
) {
    fun voor(oin: Oin): RSAKey = perOin[oin] ?: error("Geen clientsleutel voor tenant $oin")

    companion object {
        const val ALGORITME = ClientSleutelBestand.ALGORITME
        const val MIN_BITS = ClientSleutelBestand.MIN_BITS

        /** Een relatief `sleutelBestand` is relatief aan [sleutelmap]. */
        fun laad(
            tenants: List<TenantDefinition>,
            sleutelmap: Path,
        ): TenantClientSleutels {
            val sleutels = tenants.associate { it.oin to lees(it, sleutelmap) }
            val kids = HashMap<String, Oin>()
            val duimafdrukken = HashMap<String, Oin>()
            sleutels.forEach { (oin, sleutel) ->
                kids.put(sleutel.keyID, oin)?.let { error("Tenant $oin: de keyId is al in gebruik door tenant $it; elke tenant heeft een eigen clientsleutel") }
                duimafdrukken.put(sleutel.computeThumbprint().toString(), oin)?.let {
                    error("Tenant $oin: dezelfde clientsleutel als tenant $it; elke tenant heeft een eigen sleutelpaar")
                }
            }
            return TenantClientSleutels(sleutels)
        }

        private fun lees(
            tenant: TenantDefinition,
            sleutelmap: Path,
        ): RSAKey =
            try {
                ClientSleutelBestand.lees(ClientSleutelBestand.pad(tenant.authenticatie.sleutelBestand, tenant.oin.value, sleutelmap))
            } catch (e: ClientSleutelFout) {
                throw IllegalStateException("Tenant ${tenant.oin}: ${e.message}", e)
            }
    }
}

/**
 * De issuer van elke tenant ligt onder de basis-URL van de publieke broker (`nl-portal.authentication.broker-url`): een
 * parsed-URL-vergelijking op schema, host, poort en padsegmenten, geen `startsWith` op tekst.
 */
object BrokerBasis {
    fun controleer(
        tenants: List<TenantDefinition>,
        brokerUrl: String,
    ) {
        val broker = URI.create(brokerUrl)
        check(!broker.host.isNullOrEmpty() && broker.scheme in setOf("http", "https")) { "nl-portal.authentication.broker-url is geen absolute http(s)-URL" }
        tenants.forEach {
            check(valtOnder(URI.create(it.authenticatie.issuer), broker)) {
                "Tenant ${it.oin}: de issuer valt niet onder de basis-URL van de publieke broker (nl-portal.authentication.broker-url)"
            }
        }
    }

    fun valtOnder(
        issuer: URI,
        broker: URI,
    ): Boolean {
        val basis = broker.path.orEmpty().trimEnd('/')
        val pad = issuer.path.orEmpty()
        return issuer.scheme == broker.scheme &&
            issuer.host.equals(broker.host, ignoreCase = true) &&
            poort(issuer) == poort(broker) &&
            (pad == basis || pad.startsWith("$basis/"))
    }

    private fun poort(uri: URI) =
        if (uri.port != -1) {
            uri.port
        } else if (uri.scheme == "https") {
            443
        } else {
            80
        }
}

/** De endpoints van de IdP van een tenant; de standaard is de indeling van Keycloak. */
data class IdpEindpunten(
    val authorization: String,
    val token: String,
    val jwkSet: String,
    val endSession: String,
    val userInfo: String,
)

/** Hoe de endpoints uit de issuer en de interne basis-URL volgen; een tweede adapter is de testprovider. */
fun interface IdpEindpuntenIndeling {
    fun voor(
        publiekeIssuer: String,
        interneBasis: String,
    ): IdpEindpunten
}

/** Keycloak: `<issuer>/protocol/openid-connect/{auth,token,certs,logout}`; token, JWKS en userinfo via de interne basis. */
object KeycloakEindpunten : IdpEindpuntenIndeling {
    override fun voor(
        publiekeIssuer: String,
        interneBasis: String,
    ) = IdpEindpunten(
        authorization = "$publiekeIssuer/protocol/openid-connect/auth",
        token = "$interneBasis/protocol/openid-connect/token",
        jwkSet = "$interneBasis/protocol/openid-connect/certs",
        endSession = "$publiekeIssuer/protocol/openid-connect/logout",
        userInfo = "$interneBasis/protocol/openid-connect/userinfo",
    )
}

/**
 * Eén client-registratie per tenant (registratie-id is het OIN), `private_key_jwt` met authorization code en PKCE. De
 * repository geeft alleen de registratie van de tenant van het request terug; een andere registratie-id is onvindbaar.
 */
class TenantClientRegistraties(
    tenants: List<TenantDefinition>,
    private val sleutels: TenantClientSleutels,
    private val indeling: IdpEindpuntenIndeling = KeycloakEindpunten,
) : ReactiveClientRegistrationRepository {
    private val registraties = tenants.associate { it.oin.value to bouw(it) }

    override fun findByRegistrationId(registrationId: String): Mono<ClientRegistration> =
        TenantContext.currentMono().flatMap { oin ->
            if (oin.value == registrationId) Mono.justOrEmpty(registraties[registrationId]) else Mono.empty()
        }

    fun voor(oin: Oin): ClientRegistration = registraties[oin.value] ?: error("Geen client-registratie voor tenant $oin")

    fun sleutelVoor(registratie: ClientRegistration): RSAKey = sleutels.voor(Oin(registratie.registrationId))

    private fun bouw(tenant: TenantDefinition): ClientRegistration {
        val a = tenant.authenticatie
        val eindpunten = indeling.voor(a.issuer, (a.interneBasisUrl ?: a.issuer).trimEnd('/'))
        return ClientRegistration
            .withRegistrationId(tenant.oin.value)
            .clientId(a.clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}${BffNamen.CALLBACKPAD}{registrationId}")
            .scope("openid")
            .authorizationUri(eindpunten.authorization)
            .tokenUri(eindpunten.token)
            .jwkSetUri(eindpunten.jwkSet)
            .userInfoUri(eindpunten.userInfo)
            .issuerUri(a.issuer)
            .providerConfigurationMetadata(mapOf("end_session_endpoint" to eindpunten.endSession))
            .userNameAttributeName("sub")
            .clientName(tenant.naam)
            .build()
    }
}