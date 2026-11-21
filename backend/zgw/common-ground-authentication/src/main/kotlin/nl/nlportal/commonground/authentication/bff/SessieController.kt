package nl.nlportal.commonground.authentication.bff

import com.fasterxml.jackson.annotation.JsonInclude
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * De sessiestatus voor de frontend: ingelogd of niet, het type vertegenwoordigde, of er namens een ander wordt gehandeld,
 * het inlogmiddel en wanneer de sessie uiterlijk verloopt. Nooit een token, BSN of KvK-nummer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessieStatus(
    val ingelogd: Boolean,
    /** `burger` of `bedrijf`. */
    val vertegenwoordigde: String? = null,
    val namensEenAnder: Boolean? = null,
    val inlogmiddel: String? = null,
    /** Absoluut einde van de sessie (ISO-8601, UTC). */
    val verlooptOp: String? = null,
    /** Na zoveel seconden zonder activiteit verloopt de sessie; het sessie-endpoint zelf telt niet als activiteit. */
    val inactiviteitSeconden: Long? = null,
    /** Alleen als er niet is ingelogd: het pad om in te loggen (voeg `?inlogmiddel=<middel>&returnTo=<pad>` toe) en de middelen die aan staan. */
    val loginPad: String? = null,
    val inlogmiddelen: List<String>? = null,
)

@RestController
class SessieController(
    private val tenants: TenantDirectory,
) {
    @GetMapping(BffNamen.SESSIEPAD)
    suspend fun sessie(exchange: ServerWebExchange): ResponseEntity<SessieStatus> {
        val authentication = ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication as? PortalAuthentication
        val tenant = checkNotNull(tenants.forOin(TenantContext.current()))
        val status =
            if (authentication == null) {
                SessieStatus(
                    ingelogd = false,
                    loginPad = "${BffNamen.INLOGPAD}${tenant.oin}",
                    inlogmiddelen = tenant.inlogmiddelen.map { it.waarde }.sorted(),
                )
            } else {
                val aangemaakt = (exchange.session.awaitSingle().getAttribute<Any>(SessieAttributen.AANGEMAAKT) as? Long)?.let(Instant::ofEpochMilli)
                val identiteit = authentication.identiteit
                SessieStatus(
                    ingelogd = true,
                    vertegenwoordigde = if (identiteit.vertegenwoordigde is Vertegenwoordigde.Burger) "burger" else "bedrijf",
                    namensEenAnder = false,
                    inlogmiddel = identiteit.gebruiker.inlogmiddel.waarde,
                    verlooptOp = aangemaakt?.plus(tenant.sessie.absoluut)?.toString(),
                    inactiviteitSeconden = tenant.sessie.inactiviteit.seconds,
                )
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status)
    }
}
