package nl.nlportal.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * Er is precies één authenticatiepad (ADR 0022 besluit 14, epic #499): de backend is OAuth2-client van de Keycloak-realm van de
 * tenant (BFF) en leest de identiteit uit de sessie. Er is geen resource-server, geen `JwtDecoder` en geen token exchange.
 * Anders dan [IdentityRules] geldt dit ook binnen de authenticatiemodule zelf.
 *
 * @param basisPakket het pakket dat wordt gecontroleerd.
 */
class AuthenticatiePadRules(
    private val tokenExchangeTypes: DescribedPredicate<JavaClass> = TOKEN_EXCHANGE,
) {
    /** Geen resource-serverconfiguratie en geen Bearer-pad. */
    fun geenResourceServer(): ArchRule =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.security.oauth2.server.resource..")
            .orShould()
            .callMethodWhere(
                DescribedPredicate.describe("configure a resource server") { call: JavaCall<*> ->
                    call.name == "oauth2ResourceServer" && call.targetOwner.name == "org.springframework.security.config.web.server.ServerHttpSecurity"
                },
            ).allowEmptyShould(false)
            .because("ADR 0022 besluit 14: een Bearer-token van buiten wordt nergens geaccepteerd; de browser heeft alleen een sessiecookie")

    /** Geen decoder voor een binnenkomend token. */
    fun geenJwtDecoder(): ArchRule =
        noClasses()
            .should()
            .dependOnClassesThat(
                DescribedPredicate.describe("a JWT decoder") { c: JavaClass ->
                    c.name in
                        setOf(
                            "org.springframework.security.oauth2.jwt.JwtDecoder",
                            "org.springframework.security.oauth2.jwt.ReactiveJwtDecoder",
                            "org.springframework.security.oauth2.jwt.NimbusJwtDecoder",
                            "org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder",
                        )
                },
            ).allowEmptyShould(false)
            .because("ADR 0022 besluit 14: alleen Spring Security valideert het ID-token van de callback; er is geen eigen decoder voor een binnenkomend token")

    /** Geen token exchange. */
    fun geenTokenExchange(): ArchRule =
        noClasses()
            .should()
            .dependOnClassesThat(tokenExchangeTypes)
            .allowEmptyShould(false)
            .because("ADR 0022 besluit 14: de backend wisselt geen token om; de identiteit komt uit de sessie, niet uit een uitgewisseld access token")

    companion object {
        private val TOKEN_EXCHANGE =
            DescribedPredicate.describe<JavaClass>("a token exchange type") { c ->
                c.simpleName.contains("TokenExchange")
            }

        val production = AuthenticatiePadRules()
    }
}
