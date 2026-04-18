package dev.yaytsa.testkit

import dev.yaytsa.domain.adaptive.AdaptiveCommand
import dev.yaytsa.domain.auth.AuthCommand
import dev.yaytsa.domain.playback.PlaybackCommand
import dev.yaytsa.domain.playlists.PlaylistCommand
import dev.yaytsa.domain.preferences.PreferencesCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain

class CapabilitiesCompletenessTest :
    FunSpec({
        val caps = JellyfinCapabilities.supportedCommands

        test("all AuthCommand subtypes are in JellyfinCapabilities") {
            AuthCommand::class.sealedSubclasses.forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all PlaybackCommand subtypes are in JellyfinCapabilities") {
            PlaybackCommand::class.sealedSubclasses.forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all PlaylistCommand subtypes are in JellyfinCapabilities") {
            PlaylistCommand::class.sealedSubclasses.forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all PreferencesCommand subtypes are in JellyfinCapabilities") {
            PreferencesCommand::class.sealedSubclasses.forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all AdaptiveCommand subtypes are in JellyfinCapabilities") {
            AdaptiveCommand::class.sealedSubclasses.forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }
    })
