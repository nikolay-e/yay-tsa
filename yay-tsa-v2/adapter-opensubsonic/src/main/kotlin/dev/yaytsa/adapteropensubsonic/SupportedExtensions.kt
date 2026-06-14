package dev.yaytsa.adapteropensubsonic

object SupportedExtensions {
    val all: List<OpenSubsonicExtension> =
        listOf(
            OpenSubsonicExtension(name = "songLyrics", versions = listOf(1)),
            OpenSubsonicExtension(name = "apiKeyAuthentication", versions = listOf(1)),
        )
}
