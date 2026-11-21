package nl.nlportal.proto

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.security.Principal

@RestController
class SessionController {
    @GetMapping("/api/session")
    fun session(principal: Principal, exchange: ServerWebExchange) =
        mapOf("sub" to principal.name, "tenant" to exchange.tenant.id)
}
