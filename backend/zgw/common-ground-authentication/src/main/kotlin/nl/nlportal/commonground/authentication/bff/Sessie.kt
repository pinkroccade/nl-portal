package nl.nlportal.commonground.authentication.bff

import nl.nlportal.core.tenancy.Betrouwbaarheidsniveau
import nl.nlportal.core.tenancy.Inlogmiddel
import nl.nlportal.core.tenancy.TenantContext
import nl.nlportal.core.tenancy.TenantDirectory
import nl.nlportal.portal.authentication.domain.Gebruiker
import nl.nlportal.portal.authentication.domain.Identiteit
import nl.nlportal.portal.authentication.domain.PortalAuthentication
import nl.nlportal.portal.authentication.domain.Soort
import nl.nlportal.portal.authentication.domain.Vertegenwoordigde
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.server.context.ServerSecurityContextRepository
import org.springframework.session.SessionIdGenerator
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebSession
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

/** Wat de authenticatie meedraagt naast de identiteit: het ID-token voor `id_token_hint` bij het uitloggen. Alleen de BFF leest het. */
data class SessieDetails(
    val idToken: String,
) {
    override fun toString() = "SessieDetails(idToken=***)"
}

/** 256 bit uit een `SecureRandom`, base64url; Spring Session gebruikt standaard een UUID (122 bit, waarvan 6 vast). */
class SecureSessionIdGenerator : SessionIdGenerator {
    private val random = SecureRandom()

    override fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
}

/**
 * Wat er in de sessie staat: alleen de afgeleide identiteit en het ID-token, als JSON met een vaste vorm (geen Java-serialisatie van
 * een `OidcUser`, ASVS 1.5.2). Geen access- of refreshtoken.
 */
data class OpgeslagenIdentiteit(
    val issuer: String,
    val subject: String,
    val inlogmiddel: String,
    val niveau: String,
    val soort: String,
    val nummer: String,
    val vestiging: String?,
    val idToken: String,
) {
    override fun toString() = "OpgeslagenIdentiteit(issuer=$issuer)"

    fun naarAuthentication(): PortalAuthentication {
        val middel = Inlogmiddel.entries.first { it.waarde == inlogmiddel }
        val niveau = Betrouwbaarheidsniveau.entries.first { it.waarde == niveau }
        val vertegenwoordigde =
            when (Soort.entries.first { it.code == soort }) {
                Soort.BSN -> Vertegenwoordigde.Burger(nummer)
                Soort.KVK -> Vertegenwoordigde.Bedrijf(nummer, vestiging)
            }
        return PortalAuthentication(Identiteit(Gebruiker(issuer, subject, middel, niveau), vertegenwoordigde), AuthorityUtils.NO_AUTHORITIES)
            .also { it.details = SessieDetails(idToken) }
    }

    companion object {
        private val mapper: JsonMapper = jacksonMapperBuilder().build()

        fun van(authentication: PortalAuthentication): OpgeslagenIdentiteit? {
            val details = authentication.details as? SessieDetails ?: return null
            val i = authentication.identiteit
            return OpgeslagenIdentiteit(
                issuer = i.gebruiker.issuer,
                subject = i.gebruiker.subject,
                inlogmiddel = i.gebruiker.inlogmiddel.waarde,
                niveau = i.gebruiker.niveau.waarde,
                soort = i.vertegenwoordigde.soort.code,
                nummer = i.vertegenwoordigde.identificatie,
                vestiging = (i.vertegenwoordigde as? Vertegenwoordigde.Bedrijf)?.vestigingsnummer,
                idToken = details.idToken,
            )
        }

        fun parse(json: String): OpgeslagenIdentiteit? =
            try {
                mapper.readValue<OpgeslagenIdentiteit>(json)
            } catch (e: tools.jackson.core.JacksonException) {
                null
            }

        fun serialiseer(identiteit: OpgeslagenIdentiteit): String = mapper.writeValueAsString(identiteit)
    }
}

/** De sleutels van de sessieattributen. Alleen tekst en getallen: een sessie die iets anders bevat is niet van ons. */
object SessieAttributen {
    const val IDENTITEIT = "nlp.identiteit"
    const val TENANT = "nlp.tenant"
    const val AANGEMAAKT = "nlp.aangemaakt"
    const val LAATSTE_ACTIVITEIT = "nlp.laatsteActiviteit"
}

/**
 * Bewaart de [SecurityContext] in de sessie, maar alleen als afgeleide identiteit plus het ID-token. Bij opslaan krijgt de sessie
 * een nieuw ID (session fixation, ASVS 7.2.3), met de tenant als gecontroleerd attribuut en de tijdstippen voor de
 * inactiviteits- en absolute timeout.
 */
class SessieSecurityContextRepository(
    private val clock: Clock,
    private val tenants: TenantDirectory,
) : ServerSecurityContextRepository {
    override fun save(
        exchange: ServerWebExchange,
        context: SecurityContext?,
    ): Mono<Void> {
        // Uitloggen en een lege context (`save(null)`) wissen niets: de [UitlogHandler] heeft het ID-token nog nodig en beëindigt de sessie.
        val authentication = naarPortal(context?.authentication)
        return exchange.session.flatMap { session ->
            if (authentication == null) {
                Mono.empty()
            } else {
                val opgeslagen = OpgeslagenIdentiteit.van(authentication) ?: error("Een authenticatie zonder ID-token kan niet in de sessie")
                TenantContext.currentMono().flatMap { oin ->
                    val tenant = tenants.forOin(oin) ?: error("Onbekende tenant bij het opslaan van de sessie")
                    wis(session)
                    session.changeSessionId().then(
                        Mono.fromRunnable {
                            val nu = clock.instant().toEpochMilli()
                            session.attributes[SessieAttributen.IDENTITEIT] = OpgeslagenIdentiteit.serialiseer(opgeslagen)
                            session.attributes[SessieAttributen.TENANT] = oin.value
                            session.attributes[SessieAttributen.AANGEMAAKT] = nu
                            session.attributes[SessieAttributen.LAATSTE_ACTIVITEIT] = nu
                            // TTL van de sessiestore als vangnet; de bewaking handhaaft de exacte tijden
                            session.maxIdleTime = tenant.sessie.inactiviteit.plus(TTL_MARGE)
                        },
                    )
                }
            }
        }
    }

    override fun load(exchange: ServerWebExchange): Mono<SecurityContext> =
        exchange.session.flatMap { session ->
            val json = session.getAttribute<String>(SessieAttributen.IDENTITEIT)
            val authentication = json?.let { OpgeslagenIdentiteit.parse(it) }?.runCatching { naarAuthentication() }?.getOrNull()
            if (authentication == null) Mono.empty() else Mono.just(SecurityContextImpl(authentication))
        }

    /** De callback levert een `OAuth2AuthenticationToken` met een [BffOidcUser]; daarna werkt alleen de [PortalAuthentication]. */
    private fun naarPortal(authentication: Authentication?): PortalAuthentication? =
        when (authentication) {
            is PortalAuthentication -> authentication
            is OAuth2AuthenticationToken ->
                (authentication.principal as? BffOidcUser)?.let { user ->
                    PortalAuthentication(user.identiteit, AuthorityUtils.NO_AUTHORITIES).also { it.details = SessieDetails(user.idToken.tokenValue) }
                }
            else -> null
        }

    private fun wis(session: WebSession) {
        listOf(SessieAttributen.IDENTITEIT, SessieAttributen.TENANT, SessieAttributen.AANGEMAAKT, SessieAttributen.LAATSTE_ACTIVITEIT).forEach { session.attributes.remove(it) }
    }

    companion object {
        val TTL_MARGE: Duration = Duration.ofMinutes(2)
    }
}

