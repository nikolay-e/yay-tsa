package dev.yaytsa.archunit

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test

class KonsistStructuralTest {
    @Test
    fun `handlers in core-domain are objects`() {
        Konsist
            .scopeFromModule("core-domain")
            .classes()
            .withNameEndingWith("Handler")
            .toList()
            .shouldBeEmpty()
    }

    @Test
    fun `aggregates have version property`() {
        Konsist
            .scopeFromModule("core-domain")
            .classes()
            .withNameEndingWith("Aggregate")
            .assertTrue { klass ->
                klass.properties().any { it.name == "version" }
            }
    }

    @Test
    fun `domain data classes have no var properties`() {
        Konsist
            .scopeFromModule("core-domain")
            .classes()
            .filter { it.hasDataModifier }
            .assertTrue { klass ->
                klass.properties().none { it.isVar }
            }
    }

    @Test
    fun `use cases are not annotated with Spring stereotypes`() {
        Konsist
            .scopeFromModule("core-application")
            .classes()
            .withNameEndingWith("UseCases")
            .assertTrue { klass ->
                klass.annotations.none {
                    it.name in listOf("Component", "Service", "Repository")
                }
            }
    }

    @Test
    fun `command sealed interfaces extend Command`() {
        Konsist
            .scopeFromModule("core-domain")
            .interfaces()
            .filter { it.hasSealedModifier }
            .filter { it.name.endsWith("Command") }
            .assertTrue { iface ->
                iface.parents().any { it.name == "Command" }
            }
    }

    @Test
    fun `deps classes are data classes with only val properties`() {
        Konsist
            .scopeFromModule("core-domain")
            .classes()
            .filter { it.hasDataModifier }
            .filter { it.name.endsWith("Deps") }
            .assertTrue { klass ->
                klass.properties().none { it.isVar }
            }
    }
}
