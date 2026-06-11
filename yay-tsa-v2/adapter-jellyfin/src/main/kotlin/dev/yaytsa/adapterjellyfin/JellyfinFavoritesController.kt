package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.HttpFailureTranslator
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.preferences.ReorderFavorites
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
class JellyfinFavoritesController(
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val clock: Clock,
    @Qualifier("jellyfinCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
    private val failureTranslator: HttpFailureTranslator,
) {
    @PostMapping("/UserFavoriteItems/{itemId}")
    fun addFavorite(
        @PathVariable itemId: String,
        @RequestParam(required = false) userId: String?,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (userId != null && userId != principal.name) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val uid = UserId(principal.name)
        val prefs =
            preferencesQueries.find(uid) ?: dev.yaytsa.domain.preferences.UserPreferencesAggregate
                .empty(uid)
        val cmd = SetFavorite(uid, TrackId(itemId), clock.now())
        val ctx = ctxFactory.create(uid, prefs.version)
        return toResponse(preferencesUseCases.execute(cmd, ctx))
    }

    @DeleteMapping("/UserFavoriteItems/{itemId}")
    fun removeFavorite(
        @PathVariable itemId: String,
        @RequestParam(required = false) userId: String?,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (userId != null && userId != principal.name) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val uid = UserId(principal.name)
        val prefs =
            preferencesQueries.find(uid) ?: dev.yaytsa.domain.preferences.UserPreferencesAggregate
                .empty(uid)
        val cmd = UnsetFavorite(uid, TrackId(itemId))
        val ctx = ctxFactory.create(uid, prefs.version)
        return toResponse(preferencesUseCases.execute(cmd, ctx))
    }

    data class FavoriteOrderRequest(
        val UserId: String? = null,
        val ItemIds: List<String>,
    )

    @PostMapping("/Items/FavoriteOrder")
    fun reorderFavorites(
        @RequestBody request: FavoriteOrderRequest,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (request.UserId != null && request.UserId != principal.name) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        request.ItemIds.forEach { require(it.isNotBlank()) { "ItemIds must not contain blank values" } }
        val uid = UserId(principal.name)
        val prefs =
            preferencesQueries.find(uid) ?: dev.yaytsa.domain.preferences.UserPreferencesAggregate
                .empty(uid)
        val cmd = ReorderFavorites(uid, request.ItemIds.map { TrackId(it) })
        val ctx = ctxFactory.create(uid, prefs.version)
        return toResponse(preferencesUseCases.execute(cmd, ctx))
    }

    private fun toResponse(result: CommandResult<*>): ResponseEntity<Void> =
        when (result) {
            is CommandResult.Success -> ResponseEntity.ok().build()
            is CommandResult.Failed -> failureTranslator.empty(result.failure)
        }
}
