package nl.nlportal.architecture.fixtures.authclean

import org.springframework.security.config.web.server.ServerHttpSecurity

/** De BFF-vorm: oauth2Login, geen resource-server. */
class ConfiguresLogin {
    fun configure(http: ServerHttpSecurity) = http.oauth2Login { }
}
