package dev.yaytsa.persistence.preferences.mapper

import dev.yaytsa.domain.preferences.Favorite
import dev.yaytsa.domain.preferences.PreferenceContract
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.persistence.preferences.entity.FavoriteEntity
import dev.yaytsa.persistence.preferences.entity.PreferenceContractEntity
import dev.yaytsa.persistence.preferences.entity.UserPreferencesEntity
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId

fun toDomain(
    root: UserPreferencesEntity,
    favorites: List<FavoriteEntity>,
    contract: PreferenceContractEntity?,
): UserPreferencesAggregate =
    UserPreferencesAggregate(
        userId = UserId(root.userId),
        favorites = favorites.sortedBy { it.position }.map { it.toDomain() },
        preferenceContract = contract?.toDomain(),
        version = AggregateVersion(root.version),
    )

fun FavoriteEntity.toDomain(): Favorite =
    Favorite(
        trackId = TrackId(trackId),
        favoritedAt = favoritedAt,
        position = position,
    )

fun PreferenceContractEntity.toDomain(): PreferenceContract =
    PreferenceContract(
        hardRules = hardRules ?: "",
        softPrefs = softPrefs ?: "",
        djStyle = djStyle ?: "",
        redLines = redLines ?: "",
        updatedAt = updatedAt,
    )

fun UserPreferencesAggregate.toRootEntity(): UserPreferencesEntity =
    UserPreferencesEntity(
        userId = userId.value,
        version = version.value,
    )

fun UserPreferencesAggregate.toFavoriteEntities(): List<FavoriteEntity> =
    favorites.map { fav ->
        FavoriteEntity(
            userId = userId.value,
            trackId = fav.trackId.value,
            favoritedAt = fav.favoritedAt,
            position = fav.position,
        )
    }

fun UserPreferencesAggregate.toContractEntity(): PreferenceContractEntity? =
    preferenceContract?.let { contract ->
        PreferenceContractEntity(
            userId = userId.value,
            hardRules = contract.hardRules,
            softPrefs = contract.softPrefs,
            djStyle = contract.djStyle,
            redLines = contract.redLines,
            updatedAt = contract.updatedAt,
        )
    }
