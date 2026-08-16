package org.kert0n.medappserver.services.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.kert0n.medappserver.controller.ProblemResponseWriter
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Caps token requests per client address.
 *
 * Deliberately a filter rather than a check inside the controller: the bcrypt
 * verification happens in [org.springframework.security.web.authentication.www.BasicAuthenticationFilter],
 * so by the time a controller method runs the CPU has already been spent and rejecting
 * there would protect nothing.
 *
 * Not a `@Component`: a Filter bean would be auto-registered for every request, and this
 * belongs only to the token-issuing chain. It is wired up in [SecurityConfiguration].
 */
class LoginThrottleFilter(
    private val securityService: SecurityService,
    private val problems: ProblemResponseWriter
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // remoteAddr, not the raw header: with forward-headers-strategy=native Tomcat's
        // RemoteIpValve has already resolved the real client address before any filter
        // runs, and only for peers it trusts.
        val clientAddress = request.remoteAddr

        if (!securityService.isLoginAllowed(clientAddress)) {
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS)
            return
        }

        securityService.recordLoginAttempt(clientAddress)
        filterChain.doFilter(request, response)
    }
}
