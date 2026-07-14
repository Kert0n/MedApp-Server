package org.kert0n.medappserver.services.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Uses the address already resolved by the servlet container. In production Tomcat's
 * RemoteIpValve accepts forwarded headers only from the configured Caddy network.
 */
@Component
class ClientAddressProvider {
    fun getClientAddress(request: HttpServletRequest): String = request.remoteAddr
}
