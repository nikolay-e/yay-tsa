package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/rest")
class SubsonicUserController(
    private val authQueries: AuthQueries,
    private val support: SubsonicEndpointSupport,
) {
    @GetMapping("/getUser", "/getUser.view")
    fun getUser(
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val caller =
            authQueries.findUser(UserId(principal.name))
                ?: return support.notFound("User", principal.name, f)
        val target = resolveUserForView(caller, username) ?: return support.notFound("User", username ?: principal.name, f)
        return support.write(
            ok { copy(user = UserDetail(username = target.username, adminRole = target.isAdmin)) },
            f,
        )
    }

    private fun resolveUserForView(
        caller: UserAggregate,
        username: String?,
    ): UserAggregate? {
        if (username == null || username == caller.username) return caller
        if (!caller.isAdmin) throw SubsonicApiException(50, "Only admins can view other users")
        return authQueries.findByUsername(username)
    }
}
