package dev.yaytsa.persistence.library

object AlbumCoverFilenames {
    val all: List<String> =
        listOf("cover", "folder", "front", "album").flatMap { base ->
            listOf("jpg", "jpeg", "png", "webp").map { extension -> "$base.$extension" }
        }
}
