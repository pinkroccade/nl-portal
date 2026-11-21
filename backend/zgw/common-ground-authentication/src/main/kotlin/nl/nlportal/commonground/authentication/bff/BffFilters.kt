package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.tenancy.Omgevingstype
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDefinition
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.core.tenancy.TenantWebFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant

/**
 * De origin van de tenant van dit request: `schema://host[:poort]`. De host komt uit dezelfde bron als de tenantresolutie
 * (de host na de vertrouwensregels voor forwarded-headers); het schema is `https`, behalve in een ontwikkelomgeving, waar het
 * schema van het request telt. Redirect-URI's, de Origin-regel en de post-logout-URI gebruiken allemaal deze ene afleiding.
 */
object TenantOrigin {
    fun van(
        exchange: ServerWebExchange,
        tenant: TenantDefinition,
    ): String {
        val ruw = checkNotNull(exchange.getAttribute<String>(TenantWebFilter.HOST_ATTRIBUUT)) { "Geen tenanthost: het request liep niet via de tenantresolutie" }
        val host = checkNotNull(TenantDirectory.normaliseerHost(ruw))
        val poort = if (':' in ruw) ":" + ruw.substringAfterLast(':') else ""
        val schema = if (tenant.omgevingstype == Omgevingstype.ONTWIKKELING) exchange.request.uri.scheme else "https"
        return "$schema://$host$poort"
    }
}

/**
 * De managementpoort is een kindcontext die de filters van de hoofdcontext ziet maar geen tenantresolutie heeft (en dus geen
 * host-attribuut). Daar horen de tenantfilters van de BFF niet te draaien.
 */
private fun zonderTenantresolutie(exchange: ServerWebExchange) = exchange.getAttribute<String>(TenantWebFilter.HOST_ATTRIBUUT) == null

private fun afwijzen(
    exchange: ServerWebExchange,
    status: HttpStatus,
): Mono<Void> {
    exchange.response.statusCode = status
    return exchange.response.setComplete()
}

/**
 * Zet de authority van de request-URI op de origin van de tenant, zodat `{baseUrl}` in de redirect-URI (en alles wat daar
 * verder van afhangt) dezelfde host gebruikt als de tenantresolutie. Zonder dit bouwt Spring `{baseUrl}` uit de request-URI,
 * die achter een ingress anders kan zijn dan de host waaruit de tenant volgde.
 */
class TenantOriginFilter(
    private val tenants: TenantDirectory,
) : WebFilter,
    Ordered {
    override fun getOrder() = ORDER

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        if (zonderTenantresolutie(exchange)) return chain.filter(exchange)
        return TenantContext.currentMono().flatMap { oin ->
            val tenant = tenants.forOin(oin) ?: return@flatMap afwijzen(exchange, HttpStatus.NOT_FOUND)
            val origin = java.net.URI.create(TenantOrigin.van(exchange, tenant))
            val uri =
                UriComponentsBuilder
                    .fromUri(exchange.request.uri)
                    .scheme(origin.scheme)
                    .host(origin.host)
                    .port(origin.port)
                    .build(true)
                    .toUri()
            chain.filter(exchange.mutate().request { it.uri(uri) }.build())
        }
    }

    companion object {
        const val ORDER = TenantWebFilter.ORDER + 10
    }
}

/**
 * De Origin-regel voor muterende requests (ASVS 3.5.x, RFC 10017): alles behalve `GET`, `HEAD` en `OPTIONS` wordt alleen
 * toegelaten als `Origin` exact gelijk is aan de origin van de tenant, of, bij een ontbrekende `Origin`, `Sec-Fetch-Site:
 * same-origin`; zonder beide wordt het geweigerd. De enige uitzondering is de postsale-webhook (eigen handtekeningcontrole).
 * GraphQL accepteert alleen `application/json`, zodat een formulier of `text/plain` geen mutatie kan starten.
 */
class OriginRegelFilter(
    private val tenants: TenantDirectory,
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : WebFilter,
    Ordered {
    override fun getOrder() = TenantOriginFilter.ORDER + 10

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val methode = exchange.request.method
        if (zonderTenantresolutie(exchange) || methode in VEILIG) return chain.filter(exchange)
        val pad = exchange.request.path.value()
        if (pad == BffNamen.POSTSALE_WEBHOOK) return chain.filter(exchange)
        return TenantContext.currentMono().flatMap { oin ->
            val tenant = tenants.forOin(oin) ?: return@flatMap afwijzen(exchange, HttpStatus.NOT_FOUND)
            val headers = exchange.request.headers
            val origin = headers.origin
            val toegestaan =
                if (origin != null) {
                    origin == TenantOrigin.van(exchange, tenant)
                } else {
                    headers.getFirst("Sec-Fetch-Site") == "same-origin"
                }
            if (!toegestaan) {
                gebeurtenissen.registreer(Beveiligingsgebeurtenis(GebeurtenisType.ORIGIN_GEWEIGERD, oin.value, null, "geweigerd", Reden.ORIGIN))
                return@flatMap afwijzen(exchange, HttpStatus.FORBIDDEN)
            }
            if (pad == BffNamen.GRAPHQLPAD && headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) != true) {
                return@flatMap afwijzen(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            }
            chain.filter(exchange)
        }
    }

    private companion object {
        val VEILIG = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)
    }
}

/**
 * Het pad van de login en de callback hoort bij de tenant van de host: een andere registratie-id geeft 404. Voor de callback
 * geldt RFC 9207: de `iss`-parameter van de authorization response moet precies één keer voorkomen en exact gelijk zijn aan de
 * issuer van de tenant. Ontbreekt hij, dan weigeren we (de broker ondersteunt hem). Bij een weigering gaat de pre-auth-sessie weg
 * en verloopt het flowcookie.
 */
class RegistratiePadFilter(
    private val tenants: TenantDirectory,
    private val preAuth: PreAutorisatieRepository,
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : WebFilter,
    Ordered {
    override fun getOrder() = TenantOriginFilter.ORDER + 20

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val pad = exchange.request.path.value()
        if (zonderTenantresolutie(exchange)) return chain.filter(exchange)
        val prefix = listOf(BffNamen.INLOGPAD, BffNamen.CALLBACKPAD).firstOrNull { pad.startsWith(it) } ?: return chain.filter(exchange)
        return TenantContext.currentMono().flatMap { oin ->
            val tenant = tenants.forOin(oin) ?: return@flatMap afwijzen(exchange, HttpStatus.NOT_FOUND)
            val id = pad.removePrefix(prefix)
            if (id != oin.value) return@flatMap afwijzen(exchange, HttpStatus.NOT_FOUND)
            if (prefix != BffNamen.CALLBACKPAD) return@flatMap chain.filter(exchange)
            val iss = exchange.request.queryParams["iss"]
            if (iss != null && iss.size == 1 && iss[0] == tenant.authenticatie.issuer) {
                chain.filter(exchange)
            } else {
                gebeurtenissen.registreer(Beveiligingsgebeurtenis(GebeurtenisType.ISSUER_GEWEIGERD, oin.value, null, "geweigerd", Reden.ISSUER))
                preAuth.verwerp(exchange).then(afwijzen(exchange, HttpStatus.BAD_REQUEST))
            }
        }
    }
}

/**
 * Bewaakt de portaalsessie bij elk request: de sessie hoort bij de tenant van de host (een cookie van De Bosrand op Het Wijde
 * Veld wordt geweigerd, met een securityevent), en is niet langer dan de absolute duur geleden aangemaakt noch langer dan de
 * inactiviteitsduur ongebruikt (per tenant, met een injecteerbare [Clock]). Een afgewezen sessie wordt ongeldig gemaakt; het
 * request gaat daarna ongeauthenticeerd verder (401). Het sessie-endpoint telt niet als activiteit, zodat pollen een sessie niet openhoudt.
 */
class SessieBewakingFilter(
    private val clock: Clock,
    private val tenants: TenantDirectory,
    private val gebeurtenissen: Beveiligingsgebeurtenissen,
) : WebFilter,
    Ordered {
    override fun getOrder() = TenantOriginFilter.ORDER + 30

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        if (zonderTenantresolutie(exchange) || exchange.request.cookies.getFirst(BffNamen.SESSIE_COOKIE) == null) return chain.filter(exchange)
        return TenantContext.currentMono().flatMap { oin ->
            val tenant = tenants.forOin(oin) ?: return@flatMap afwijzen(exchange, HttpStatus.NOT_FOUND)
            exchange.session.flatMap { session ->
                if (!session.isStarted) return@flatMap chain.filter(exchange)
                val nu = clock.instant()
                val opgeslagenTenant = session.getAttribute<String>(SessieAttributen.TENANT)
                val aangemaakt = (session.getAttribute<Any>(SessieAttributen.AANGEMAAKT) as? Long)?.let(Instant::ofEpochMilli)
                val laatste = (session.getAttribute<Any>(SessieAttributen.LAATSTE_ACTIVITEIT) as? Long)?.let(Instant::ofEpochMilli)
                val reden =
                    when {
                        opgeslagenTenant != oin.value -> Reden.TENANT
                        aangemaakt == null || laatste == null -> Reden.PROTOCOL
                        !nu.isBefore(aangemaakt.plus(tenant.sessie.absoluut)) -> Reden.VERLOPEN_ABSOLUUT
                        !nu.isBefore(laatste.plus(tenant.sessie.inactiviteit)) -> Reden.VERLOPEN_INACTIVITEIT
                        else -> null
                    }
                if (reden == null) {
                    if (exchange.request.path.value() != BffNamen.SESSIEPAD) session.attributes[SessieAttributen.LAATSTE_ACTIVITEIT] = nu.toEpochMilli()
                    chain.filter(exchange)
                } else {
                    val actor = session.getAttribute<String>(SessieAttributen.IDENTITEIT)?.let { OpgeslagenIdentiteit.parse(it) }?.let { Actor(it.issuer, it.subject) }
                    val type = if (reden == Reden.TENANT) GebeurtenisType.SESSIE_TENANT_MISMATCH else GebeurtenisType.SESSIE_VERLOPEN
                    gebeurtenissen.registreer(Beveiligingsgebeurtenis(type, oin.value, actor, "geweigerd", reden))
                    session.attributes.clear()
                    session.invalidate().then(chain.filter(exchange))
                }
            }
        }
    }
}
