@file:Suppress("ktlint:standard:property-naming")

package dev.yaytsa.archunit

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["dev.yaytsa"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureTest {
    // ── Rule 1: shared depends on nothing but kotlin/java stdlib ──────────

    @ArchTest
    val `shared must not depend on Spring`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")

    @ArchTest
    val `shared must not depend on JPA`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.persistence..")

    // ── Rule 2: core-domain contexts are isolated from each other ─────────

    @ArchTest
    val `domain-auth must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.auth..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.library..",
                "dev.yaytsa.domain.playback..",
                "dev.yaytsa.domain.adaptive..",
                "dev.yaytsa.domain.preferences..",
                "dev.yaytsa.domain.playlists..",
                "dev.yaytsa.domain.ml..",
                "dev.yaytsa.domain.karaoke..",
            )

    @ArchTest
    val `domain-playback must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.playback..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.auth..",
                "dev.yaytsa.domain.library..",
                "dev.yaytsa.domain.adaptive..",
                "dev.yaytsa.domain.preferences..",
                "dev.yaytsa.domain.playlists..",
                "dev.yaytsa.domain.ml..",
                "dev.yaytsa.domain.karaoke..",
            )

    @ArchTest
    val `domain-playlists must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.playlists..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.auth..",
                "dev.yaytsa.domain.library..",
                "dev.yaytsa.domain.playback..",
                "dev.yaytsa.domain.adaptive..",
                "dev.yaytsa.domain.preferences..",
                "dev.yaytsa.domain.ml..",
                "dev.yaytsa.domain.karaoke..",
            )

    @ArchTest
    val `domain-preferences must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.preferences..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.auth..",
                "dev.yaytsa.domain.library..",
                "dev.yaytsa.domain.playback..",
                "dev.yaytsa.domain.adaptive..",
                "dev.yaytsa.domain.playlists..",
                "dev.yaytsa.domain.ml..",
                "dev.yaytsa.domain.karaoke..",
            )

    @ArchTest
    val `domain-library must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.library..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.auth..",
                "dev.yaytsa.domain.playback..",
                "dev.yaytsa.domain.adaptive..",
                "dev.yaytsa.domain.preferences..",
                "dev.yaytsa.domain.playlists..",
                "dev.yaytsa.domain.ml..",
                "dev.yaytsa.domain.karaoke..",
            )

    @ArchTest
    val `domain-adaptive must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.adaptive..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.auth..",
                "dev.yaytsa.domain.library..",
                "dev.yaytsa.domain.playback..",
                "dev.yaytsa.domain.preferences..",
                "dev.yaytsa.domain.playlists..",
                "dev.yaytsa.domain.ml..",
                "dev.yaytsa.domain.karaoke..",
            )

    @ArchTest
    val `domain-ml must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.ml..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.auth..",
                "dev.yaytsa.domain.library..",
                "dev.yaytsa.domain.playback..",
                "dev.yaytsa.domain.adaptive..",
                "dev.yaytsa.domain.preferences..",
                "dev.yaytsa.domain.playlists..",
                "dev.yaytsa.domain.karaoke..",
            )

    @ArchTest
    val `domain-karaoke must not depend on other domain contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain.karaoke..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.domain.auth..",
                "dev.yaytsa.domain.library..",
                "dev.yaytsa.domain.playback..",
                "dev.yaytsa.domain.adaptive..",
                "dev.yaytsa.domain.preferences..",
                "dev.yaytsa.domain.playlists..",
                "dev.yaytsa.domain.ml..",
            )

    // ── Rule 3: domain contexts must not depend on Spring/JPA ─────────────

    @ArchTest
    val `domain must not depend on Spring`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")

    @ArchTest
    val `domain must not depend on JPA`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.persistence..")

    @ArchTest
    val `domain must not depend on Jackson`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.fasterxml.jackson..")

    @ArchTest
    val `domain must not depend on Hibernate`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.hibernate..")

    // ── Rule 4: application-shared must not depend on Spring/JPA ──────────

    @ArchTest
    val `application-shared must not depend on Spring`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")

    @ArchTest
    val `application-shared must not depend on JPA`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.persistence..")

    // ── Rule 5: application contexts must not depend on persistence ────────

    @ArchTest
    val `application must not depend on persistence`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.yaytsa.persistence..")

    // ── Rule 6: persistence must not depend on adapters ───────────────────

    @ArchTest
    val `persistence must not depend on adapters`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.adapter..",
            )

    // ── Rule 7: application contexts may only import peer contexts' port subpackages ──

    companion object {
        private val APPLICATION_CONTEXTS =
            listOf("auth", "library", "playback", "adaptive", "preferences", "playlists", "ml", "karaoke")

        private fun nonPortClassesInPeerContexts(self: String) =
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "non-port classes in peer application contexts (not $self)",
            ) { clazz: com.tngtech.archunit.core.domain.JavaClass ->
                val pkg = clazz.packageName
                APPLICATION_CONTEXTS.filter { it != self }.any { peer ->
                    pkg.startsWith("dev.yaytsa.application.$peer") &&
                        !pkg.startsWith("dev.yaytsa.application.$peer.port")
                }
            }
    }

    @ArchTest
    val `application-auth must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.auth..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("auth"))
            .allowEmptyShould(true)

    @ArchTest
    val `application-library must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.library..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("library"))
            .allowEmptyShould(true)

    @ArchTest
    val `application-playback must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.playback..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("playback"))
            .allowEmptyShould(true)

    @ArchTest
    val `application-adaptive must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.adaptive..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("adaptive"))
            .allowEmptyShould(true)

    @ArchTest
    val `application-playlists must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.playlists..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("playlists"))
            .allowEmptyShould(true)

    @ArchTest
    val `application-preferences must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.preferences..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("preferences"))
            .allowEmptyShould(true)

    @ArchTest
    val `application-ml must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.ml..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("ml"))
            .allowEmptyShould(true)

    @ArchTest
    val `application-karaoke must only import port subpackages from peer contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application.karaoke..")
            .should()
            .dependOnClassesThat(nonPortClassesInPeerContexts("karaoke"))
            .allowEmptyShould(true)

    // ── Rule 8: adapters must not call domain handlers directly ──────────

    @ArchTest
    val `adapters must not depend on domain handlers`: ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage(
                "dev.yaytsa.adapteropensubsonic..",
                "dev.yaytsa.adaptermcp..",
                "dev.yaytsa.adaptermpd..",
            ).should()
            .dependOnClassesThat(
                com.tngtech.archunit.base.DescribedPredicate.describe(
                    "are domain handlers",
                ) { clazz ->
                    clazz.packageName.startsWith("dev.yaytsa.domain") &&
                        clazz.simpleName.endsWith("Handler")
                },
            ).allowEmptyShould(true)
            .because(
                "adapters may use domain types for protocol translation but must delegate all business logic to use cases, never calling domain handlers directly",
            )

    // ── Rule 9: persistence contexts must not cross-reference each other's entities ──

    @ArchTest
    val `persistence-playback must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.playback..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.preferences..",
                "dev.yaytsa.persistence.playlists..",
                "dev.yaytsa.persistence.auth..",
                "dev.yaytsa.persistence.library..",
                "dev.yaytsa.persistence.adaptive..",
                "dev.yaytsa.persistence.ml..",
                "dev.yaytsa.persistence.karaoke..",
            ).allowEmptyShould(true)

    @ArchTest
    val `persistence-preferences must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.preferences..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.playback..",
                "dev.yaytsa.persistence.playlists..",
                "dev.yaytsa.persistence.auth..",
                "dev.yaytsa.persistence.library..",
                "dev.yaytsa.persistence.adaptive..",
                "dev.yaytsa.persistence.ml..",
                "dev.yaytsa.persistence.karaoke..",
            ).allowEmptyShould(true)

    @ArchTest
    val `persistence-playlists must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.playlists..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.playback..",
                "dev.yaytsa.persistence.preferences..",
                "dev.yaytsa.persistence.auth..",
                "dev.yaytsa.persistence.library..",
                "dev.yaytsa.persistence.adaptive..",
                "dev.yaytsa.persistence.ml..",
                "dev.yaytsa.persistence.karaoke..",
            ).allowEmptyShould(true)

    @ArchTest
    val `persistence-auth must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.auth..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.library..",
                "dev.yaytsa.persistence.playback..",
                "dev.yaytsa.persistence.adaptive..",
                "dev.yaytsa.persistence.preferences..",
                "dev.yaytsa.persistence.playlists..",
                "dev.yaytsa.persistence.ml..",
                "dev.yaytsa.persistence.karaoke..",
            ).allowEmptyShould(true)

    @ArchTest
    val `persistence-library must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.library..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.auth..",
                "dev.yaytsa.persistence.playback..",
                "dev.yaytsa.persistence.adaptive..",
                "dev.yaytsa.persistence.preferences..",
                "dev.yaytsa.persistence.playlists..",
                "dev.yaytsa.persistence.ml..",
                "dev.yaytsa.persistence.karaoke..",
            ).allowEmptyShould(true)

    @ArchTest
    val `persistence-adaptive must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.adaptive..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.auth..",
                "dev.yaytsa.persistence.library..",
                "dev.yaytsa.persistence.playback..",
                "dev.yaytsa.persistence.preferences..",
                "dev.yaytsa.persistence.playlists..",
                "dev.yaytsa.persistence.ml..",
                "dev.yaytsa.persistence.karaoke..",
            ).allowEmptyShould(true)

    @ArchTest
    val `persistence-ml must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.ml..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.auth..",
                "dev.yaytsa.persistence.library..",
                "dev.yaytsa.persistence.playback..",
                "dev.yaytsa.persistence.adaptive..",
                "dev.yaytsa.persistence.preferences..",
                "dev.yaytsa.persistence.playlists..",
                "dev.yaytsa.persistence.karaoke..",
            ).allowEmptyShould(true)

    @ArchTest
    val `persistence-karaoke must not depend on other persistence contexts`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.persistence.karaoke..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.yaytsa.persistence.auth..",
                "dev.yaytsa.persistence.library..",
                "dev.yaytsa.persistence.playback..",
                "dev.yaytsa.persistence.adaptive..",
                "dev.yaytsa.persistence.preferences..",
                "dev.yaytsa.persistence.playlists..",
                "dev.yaytsa.persistence.ml..",
            ).allowEmptyShould(true)

    // ── Rule 10: domain must not call Instant.now() ──────────────────────
    @ArchTest
    val `domain must not call Instant_now`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain..")
            .should()
            .callMethod(java.time.Instant::class.java, "now")
            .because("domain handlers must receive time from CommandContext, not read the system clock")

    // ── Rule 11: application must not call Instant.now() ──────────────────
    @ArchTest
    val `application must not call Instant_now`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.application..")
            .should()
            .callMethod(java.time.Instant::class.java, "now")
            .because("application layer must receive time through Clock port or CommandContext")

    // ── Rule 12: domain must not call System.currentTimeMillis() ──────────
    @ArchTest
    val `domain must not call System_currentTimeMillis`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.yaytsa.domain..")
            .should()
            .callMethod(System::class.java, "currentTimeMillis")
}
