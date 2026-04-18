package dev.yaytsa.domain.preferences

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class PreferencesHandlerTest :
    FunSpec({
        val userId = UserId("user-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val t1 = TrackId("track-1")
        val t2 = TrackId("track-2")

        fun ctx(v: AggregateVersion = AggregateVersion(0)) = CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k"), v)

        fun deps(ids: Set<TrackId> = emptySet()) = PreferencesDeps(ids)

        fun prefs(
            favs: List<Favorite> = emptyList(),
            v: AggregateVersion = AggregateVersion(0),
        ) = UserPreferencesAggregate(userId, favs, null, v)

        test("SetFavorite adds track") {
            val r = PreferencesHandler.handle(prefs(), SetFavorite(userId, t1, now), ctx(), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.favorites.size shouldBe 1
            r.value.favorites[0].trackId shouldBe t1
        }

        test("SetFavorite is idempotent") {
            val existing = listOf(Favorite(t1, now, 0))
            val r = PreferencesHandler.handle(prefs(favs = existing), SetFavorite(userId, t1, now.plusSeconds(10)), ctx(), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.favorites.size shouldBe 1
        }

        test("SetFavorite rejects unknown track") {
            val r = PreferencesHandler.handle(prefs(), SetFavorite(userId, t1, now), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.NotFound>()
        }

        test("UnsetFavorite removes track") {
            val existing = listOf(Favorite(t1, now, 0))
            val r = PreferencesHandler.handle(prefs(favs = existing), UnsetFavorite(userId, t1), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.favorites.size shouldBe 0
        }

        test("UnsetFavorite for non-favorite fails") {
            val r = PreferencesHandler.handle(prefs(), UnsetFavorite(userId, t1), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.NotFound>()
        }

        test("ReorderFavorites with valid permutation succeeds") {
            val favs = listOf(Favorite(t1, now, 0), Favorite(t2, now, 1))
            val r = PreferencesHandler.handle(prefs(favs = favs), ReorderFavorites(userId, listOf(t2, t1)), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.favorites[0].trackId shouldBe t2
            r.value.favorites[1].trackId shouldBe t1
        }

        test("ReorderFavorites with invalid permutation fails") {
            val favs = listOf(Favorite(t1, now, 0))
            val r = PreferencesHandler.handle(prefs(favs = favs), ReorderFavorites(userId, listOf(t2)), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("UpdatePreferenceContract sets contract") {
            val r =
                PreferencesHandler.handle(
                    prefs(),
                    UpdatePreferenceContract(userId, "no metal", "prefer jazz", "chill", "no screaming", now),
                    ctx(),
                    deps(),
                )
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.preferenceContract!!.hardRules shouldBe "no metal"
            r.value.preferenceContract!!.djStyle shouldBe "chill"
        }

        test("version mismatch returns Conflict") {
            val r =
                PreferencesHandler.handle(
                    prefs(v = AggregateVersion(5)),
                    SetFavorite(userId, t1, now),
                    ctx(v = AggregateVersion(3)),
                    deps(setOf(t1)),
                )
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Conflict>()
        }

        test("another user cannot modify preferences") {
            val r =
                PreferencesHandler.handle(
                    prefs(),
                    SetFavorite(userId, t1, now),
                    CommandContext(UserId("other"), ProtocolId("JELLYFIN"), now, IdempotencyKey("k"), AggregateVersion(0)),
                    deps(setOf(t1)),
                )
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("SetFavorite idempotency returns unchanged snapshot") {
            val existing = listOf(Favorite(t1, now, 0))
            val r = PreferencesHandler.handle(prefs(favs = existing), SetFavorite(userId, t1, now.plusSeconds(10)), ctx(), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.favorites.size shouldBe 1
            r.newVersion shouldBe AggregateVersion(0)
        }

        test("UnsetFavorite compacts positions") {
            val favs = listOf(Favorite(t1, now, 0), Favorite(t2, now, 1), Favorite(TrackId("t3"), now, 2))
            val r = PreferencesHandler.handle(prefs(favs = favs), UnsetFavorite(userId, t2), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.favorites.size shouldBe 2
            r.value.favorites[0].position shouldBe 0
            r.value.favorites[1].position shouldBe 1
            r.value.favorites[0].trackId shouldBe t1
            r.value.favorites[1].trackId shouldBe TrackId("t3")
        }

        test("UpdatePreferenceContract overwrites existing contract") {
            val withContract =
                prefs().copy(
                    preferenceContract = PreferenceContract("old rules", "old prefs", "old style", "old lines", now),
                )
            val r =
                PreferencesHandler.handle(
                    withContract,
                    UpdatePreferenceContract(userId, "new rules", "new prefs", "new style", "new lines", now),
                    ctx(),
                    deps(),
                )
            r.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            r.value.preferenceContract!!.hardRules shouldBe "new rules"
            r.value.preferenceContract!!.softPrefs shouldBe "new prefs"
        }

        test("Favorites position correct after set-unset-set cycle") {
            // Add t1 (pos 0), add t2 (pos 1), unset t1 (compact: t2 at pos 0), add t1 back (pos 1)
            var s = prefs()
            val r1 = PreferencesHandler.handle(s, SetFavorite(userId, t1, now), ctx(s.version), deps(setOf(t1)))
            r1.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            s = r1.value

            val r2 = PreferencesHandler.handle(s, SetFavorite(userId, t2, now), ctx(s.version), deps(setOf(t2)))
            r2.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            s = r2.value

            val r3 = PreferencesHandler.handle(s, UnsetFavorite(userId, t1), ctx(s.version), deps())
            r3.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            s = r3.value

            val r4 = PreferencesHandler.handle(s, SetFavorite(userId, t1, now), ctx(s.version), deps(setOf(t1)))
            r4.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            s = r4.value

            s.favorites.size shouldBe 2
            s.favorites.sortedBy { it.position }.map { it.trackId } shouldBe listOf(t2, t1)
            s.favorites[0].position shouldBe 0
            s.favorites[1].position shouldBe 1
        }
    })
