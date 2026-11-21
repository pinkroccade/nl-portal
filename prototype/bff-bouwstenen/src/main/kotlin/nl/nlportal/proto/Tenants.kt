package nl.nlportal.proto

import com.nimbusds.jose.jwk.JWK
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Prototype-config: in het echte ontwerp is dit het tenantbestand (core.tenancy). */
@ConfigurationProperties("proto")
data class ProtoProperties(
    val tenants: List<TenantProperties> = emptyList(),
    val absoluteSessionTimeout: Duration = Duration.ofHours(8),
    val inactivityTimeout: Duration = Duration.ofMinutes(15),
    val flowCookieMaxAge: Duration = Duration.ofMinutes(5),
)

data class TenantProperties(
    val id: String,
    val hosts: List<String>,
    val clientId: String,
    val issuer: String,
    val authorizationUri: String,
    val tokenUri: String,
    val jwkSetUri: String,
    val endSessionUri: String,
    /** Pad naar een JWK (bestandsmount); de private sleutel staat nooit in de configuratie zelf. */
    val keyFile: String,
)

class Tenant(val properties: TenantProperties) {
    val id get() = properties.id
    val key: JWK = JWK.parse(Files.readString(Path.of(properties.keyFile)))

    init {
        require(key.isPrivate) { "sleutel van ${properties.id} moet private zijn" }
        require(key.keyID != null && key.algorithm != null) { "sleutel van ${properties.id} mist kid of alg" }
    }
}

class TenantRegistry(properties: ProtoProperties) {
    private val byId = properties.tenants.associate { it.id to Tenant(it) }
    private val byHost: Map<String, Tenant>

    init {
        val hosts = mutableMapOf<String, Tenant>()
        byId.values.forEach { t ->
            t.properties.hosts.forEach { h ->
                check(hosts.put(normalize(h), t) == null) { "dubbel domein $h" }
            }
        }
        check(byId.values.map { it.key.keyID }.toSet().size == byId.size) { "dubbele keyId" }
        byHost = hosts
    }

    fun byId(id: String): Tenant? = byId[id]

    fun byHost(hostHeader: String?): Tenant? = hostHeader?.let { byHost[normalize(it)] }

    fun all(): Collection<Tenant> = byId.values

    companion object {
        fun normalize(host: String): String = host.lowercase().substringBefore(':').trimEnd('.')
    }
}

private const val TENANT_ATTRIBUTE = "nlp.tenant"

val ServerWebExchange.tenant: Tenant
    get() = getAttribute<Tenant>(TENANT_ATTRIBUTE) ?: error("geen tenantcontext (fail-closed)")

val ServerWebExchange.tenantOrNull: Tenant? get() = getAttribute<Tenant>(TENANT_ATTRIBUTE)

/**
 * De tenant volgt uit de Host van het request; een onbekende host bestaat niet (404 zonder body).
 * Een registratie-id in het pad (login, callback, back-channel logout) dat niet bij de host-tenant hoort: 404.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantFilter(private val registry: TenantRegistry) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val tenant = registry.byHost(exchange.request.headers.getFirst("Host"))
        if (tenant == null) return notFound(exchange)
        exchange.attributes[TENANT_ATTRIBUTE] = tenant
        val path = exchange.request.path.value()
        val registrationId = REGISTRATION_PATHS.firstNotNullOfOrNull { prefix ->
            path.removePrefix(prefix).takeIf { path.startsWith(prefix) }
        }
        if (registrationId != null && registrationId != tenant.id) return notFound(exchange)
        return chain.filter(exchange)
    }

    private fun notFound(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.NOT_FOUND
        return exchange.response.setComplete()
    }

    private companion object {
        val REGISTRATION_PATHS = listOf(
            "/oauth2/authorization/",
            "/login/oauth2/code/",
            "/logout/connect/back-channel/",
        )
    }
}

/** Eén client-registratie per tenant, `private_key_jwt`; de sleutel hoort bij de registratie. */
class TenantClientRegistrationRepository(private val registry: TenantRegistry) : ReactiveClientRegistrationRepository {
    private val registrations = registry.all().associate { it.id to build(it.properties) }

    override fun findByRegistrationId(registrationId: String): Mono<ClientRegistration> =
        Mono.justOrEmpty(registrations[registrationId])

    fun keyFor(registration: ClientRegistration): JWK =
        registry.byId(registration.registrationId)?.key ?: error("onbekende registratie")

    private fun build(p: TenantProperties): ClientRegistration =
        ClientRegistration.withRegistrationId(p.id)
            .clientId(p.clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid")
            .authorizationUri(p.authorizationUri)
            .tokenUri(p.tokenUri)
            .jwkSetUri(p.jwkSetUri)
            .issuerUri(p.issuer)
            .providerConfigurationMetadata(mapOf("end_session_endpoint" to p.endSessionUri))
            .userNameAttributeName("sub")
            .clientName(p.id)
            .build()
}
