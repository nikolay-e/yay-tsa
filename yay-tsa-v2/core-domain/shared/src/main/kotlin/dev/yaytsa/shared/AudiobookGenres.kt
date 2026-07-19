package dev.yaytsa.shared

object AudiobookGenres {
    const val SQL_LIST: String = "'audiobook', 'audiobooks'"

    // Fragments of the correlated "entity is tagged as an audiobook" EXISTS test. Kept as const
    // (not a fun) because they are spliced into @Query / const-val native SQL, whose annotation
    // arguments must be compile-time constants. Caller places the entity-id match predicate
    // between them: "${EXISTS_OPEN}eg.entity_id = a.track_id${EXISTS_CLOSE}".
    const val EXISTS_OPEN: String =
        "EXISTS (SELECT 1 FROM core_v2_library.entity_genres eg " +
            "JOIN core_v2_library.genres g ON g.id = eg.genre_id WHERE "
    const val EXISTS_CLOSE: String = " AND lower(g.name) IN ($SQL_LIST))"

    val names: Set<String> = SQL_LIST.split(", ").map { it.trim('\'') }.toSet()
}
