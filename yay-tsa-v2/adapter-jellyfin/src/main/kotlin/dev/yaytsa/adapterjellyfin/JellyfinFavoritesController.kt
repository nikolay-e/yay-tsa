package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.preferences.ReorderFavorites
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID

@RestController
class JellyfinFavoritesController(
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val clock: Clock,
) {
    @PostMapping("/UserFavoriteItems/{itemId}")
    fun addFavorite(
        @PathVariable itemId: String,
        @RequestParam(required = false) userId: String?,
        principal: Principal,
    ): ResponseEntity<Void> {
        val uid = UserId(userId ?: principal.name)
        val prefs =
            preferencesQueries.find(uid) ?: dev.yaytsa.domain.preferences.UserPreferencesAggregate
                .empty(uid)
        val cmd = SetFavorite(uid, TrackId(itemId), clock.now())
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), prefs.version)
        preferencesUseCases.execute(cmd, ctx)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/UserFavoriteItems/{itemId}")
    fun removeFavorite(
        @PathVariable itemId: String,
        @RequestParam(required = false) userId: String?,
        principal: Principal,
    ): ResponseEntity<Void> {
        val uid = UserId(userId ?: principal.name)
        val prefs =
            preferencesQueries.find(uid) ?: dev.yaytsa.domain.preferences.UserPreferencesAggregate
                .empty(uid)
        val cmd = UnsetFavorite(uid, TrackId(itemId))
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), prefs.version)
        preferencesUseCases.execute(cmd, ctx)
        return ResponseEntity.ok().build()
    }

    data class FavoriteOrderRequest(
        val UserId: String,
        val ItemIds: List<String>,
    )

    @PostMapping("/Items/FavoriteOrder")
    fun reorderFavorites(
        @RequestBody request: FavoriteOrderRequest,
    ): ResponseEntity<Void> {
        val uid = UserId(request.UserId)
        val prefs =
            preferencesQueries.find(uid) ?: dev.yaytsa.domain.preferences.UserPreferencesAggregate
                .empty(uid)
        val cmd = ReorderFavorites(uid, request.ItemIds.map { TrackId(it) })
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), prefs.version)
        preferencesUseCases.execute(cmd, ctx)
        return ResponseEntity.ok().build()
    }
}
