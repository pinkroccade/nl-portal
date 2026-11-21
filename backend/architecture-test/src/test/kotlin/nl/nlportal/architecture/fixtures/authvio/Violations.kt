package nl.nlportal.architecture.fixtures.authvio

import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.endpoint.TokenExchangeGrantRequest
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder

/** Een tweede authenticatiepad: een resource-server in de beveiligingsketen. */
class ConfiguresResourceServer {
    fun configure(http: ServerHttpSecurity) = http.oauth2ResourceServer { it.jwt { } }
}

/** Een tweede authenticatiepad: een decoder die een binnenkomend token valideert. */
class HoldsJwtDecoder {
    fun decoder(decoder: ReactiveJwtDecoder) = decoder
}

/** Een tweede authenticatiepad: token exchange. */
class DoesTokenExchange {
    fun type(): Class<*> = TokenExchangeGrantRequest::class.java
}
