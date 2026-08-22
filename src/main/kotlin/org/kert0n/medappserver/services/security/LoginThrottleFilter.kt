package org.kert0n.medappserver.services.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.kert0n.medappserver.controller.ProblemResponseWriter
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Ограничивает число запросов токена с одного адреса клиента.
 *
 * Фильтром, а не проверкой в контроллере: bcrypt считается внутри
 * [org.springframework.security.web.authentication.www.BasicAuthenticationFilter], и к моменту,
 * когда доходит до метода контроллера, процессор уже потрачен. И не `@Component`: бин-фильтр
 * зарегистрировался бы на каждый запрос, а это дело только цепочки выдачи токена.
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
        // `remoteAddr`, а не сырой заголовок: при `forward-headers-strategy=native` адрес
        // клиента уже разобрал `RemoteIpValve` томката — и только для доверенных узлов.
        val clientAddress = request.remoteAddr

        if (!securityService.isLoginAllowed(clientAddress)) {
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS)
            return
        }

        securityService.recordLoginAttempt(clientAddress)
        filterChain.doFilter(request, response)
    }
}
