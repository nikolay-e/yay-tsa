package dev.yaytsa.shared

object AudiobookGenres {
    const val SQL_LIST: String = "'audiobook', 'audiobooks'"

    val names: Set<String> = SQL_LIST.split(", ").map { it.trim('\'') }.toSet()
}
