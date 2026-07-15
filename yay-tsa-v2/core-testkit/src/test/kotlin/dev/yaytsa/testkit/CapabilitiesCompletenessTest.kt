package dev.yaytsa.testkit

import dev.yaytsa.domain.adaptive.AdaptiveCommand
import dev.yaytsa.domain.auth.AuthCommand
import dev.yaytsa.domain.playback.PlaybackCommand
import dev.yaytsa.domain.playlists.PlaylistCommand
import dev.yaytsa.domain.preferences.PreferencesCommand
import dev.yaytsa.shared.Command
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import kotlin.reflect.KClass

private fun concreteCommandClasses(root: KClass<out Command>): List<KClass<out Command>> =
    root.sealedSubclasses.flatMap { subclass ->
        if (subclass.isSealed) concreteCommandClasses(subclass) else listOf(subclass)
    }

class CapabilitiesCompletenessTest :
    FunSpec({
        val caps = JellyfinCapabilities.supportedCommands

        test("all AuthCommand subtypes are in JellyfinCapabilities") {
            concreteCommandClasses(AuthCommand::class).forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all PlaybackCommand subtypes are in JellyfinCapabilities") {
            concreteCommandClasses(PlaybackCommand::class).forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all PlaylistCommand subtypes are in JellyfinCapabilities") {
            concreteCommandClasses(PlaylistCommand::class).forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all PreferencesCommand subtypes are in JellyfinCapabilities") {
            concreteCommandClasses(PreferencesCommand::class).forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }

        test("all AdaptiveCommand subtypes are in JellyfinCapabilities") {
            concreteCommandClasses(AdaptiveCommand::class).forEach { cmdClass ->
                caps shouldContain cmdClass
            }
        }
    })
