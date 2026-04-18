plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "dev.yaytsa"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Only apply plugins to leaf projects
    if (childProjects.isNotEmpty()) return@subprojects

    // Set unique group based on parent path to avoid Gradle capability conflicts
    // e.g., :core-domain:shared → group "dev.yaytsa.core-domain"
    val segments = path.removePrefix(":").split(":")
    if (segments.size > 1) {
        group = "dev.yaytsa.${segments.dropLast(1).joinToString(".")}"
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // Unique jar names to prevent collisions in bootJar (e.g. core-domain-shared, core-application-shared)
    base.archivesName.set(path.removePrefix(":").replace(":", "-"))

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("detekt.yml"))
        buildUponDefaultConfig = true
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            ktlint()
            targetExclude("build/**")
        }
        kotlinGradle {
            ktlint()
            targetExclude("build/**")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Detekt 1.x is incompatible with Kotlin 2.1 — exclude from build lifecycle
    tasks.named("check") {
        setDependsOn(dependsOn.filterNot { it.toString().contains("detekt") })
    }
}

tasks.register("generateContractMatrix") {
    group = "documentation"
    description = "Generates contract verification matrix from source code"

    val outputFile = file("docs/generated/contract-matrix.md")

    doLast {
        val contexts = listOf(
            Triple("auth",
                "core-application/auth/src/main/kotlin/dev/yaytsa/application/auth/AuthUseCases.kt",
                "core-domain/auth/src/main/kotlin/dev/yaytsa/domain/auth/AuthHandler.kt"),
            Triple("playback",
                "core-application/playback/src/main/kotlin/dev/yaytsa/application/playback/PlaybackUseCases.kt",
                "core-domain/playback/src/main/kotlin/dev/yaytsa/domain/playback/PlaybackHandler.kt"),
            Triple("playlists",
                "core-application/playlists/src/main/kotlin/dev/yaytsa/application/playlists/PlaylistUseCases.kt",
                "core-domain/playlists/src/main/kotlin/dev/yaytsa/domain/playlists/PlaylistHandler.kt"),
            Triple("preferences",
                "core-application/preferences/src/main/kotlin/dev/yaytsa/application/preferences/PreferencesUseCases.kt",
                "core-domain/preferences/src/main/kotlin/dev/yaytsa/domain/preferences/PreferencesHandler.kt"),
            Triple("adaptive",
                "core-application/adaptive/src/main/kotlin/dev/yaytsa/application/adaptive/AdaptiveUseCases.kt",
                "core-domain/adaptive/src/main/kotlin/dev/yaytsa/domain/adaptive/AdaptiveHandler.kt"),
        )

        val sb = StringBuilder()
        sb.appendLine("# Contract Verification Matrix")
        sb.appendLine()
        sb.appendLine("Auto-generated from source code. Do not edit manually.")
        sb.appendLine()
        sb.appendLine("| Context | Aggregate | OCC | Idempotency | Outbox | Protocol Check | Deps Pattern | Lease |")
        sb.appendLine("|---------|-----------|-----|-------------|--------|----------------|--------------|-------|")

        for ((context, useCasePath, handlerPath) in contexts) {
            val useCaseSource = file(useCasePath)
            val handlerSource = file(handlerPath)
            if (!useCaseSource.exists()) {
                sb.appendLine("| $context | ? | ? | ? | ? | ? | ? | ? |")
                continue
            }
            val useCaseContent = useCaseSource.readText()
            val handlerContent = if (handlerSource.exists()) handlerSource.readText() else ""

            val hasOcc = handlerContent.contains("checkVersion") ||
                handlerContent.contains("version != ctx.expectedVersion") ||
                handlerContent.contains("ctx.expectedVersion")
            val hasIdempotency = useCaseContent.contains("idempotencyStore")
            val hasOutbox = useCaseContent.contains("outbox.enqueue")
            val hasProtocolCheck = useCaseContent.contains("isCommandSupported")
            val hasDeps = useCaseContent.contains("loadDeps") || useCaseContent.contains("trackValidator")
            val hasLease = useCaseContent.contains("lease") || useCaseContent.contains("Lease") || handlerContent.contains("withLease") || handlerContent.contains("Lease")

            // Extract aggregate type from imports
            val aggregateMatch = Regex("""import.*\.(\w+Aggregate)""").find(useCaseContent)
            val aggregate = aggregateMatch?.groupValues?.get(1) ?: "?"

            fun check(b: Boolean) = if (b) "✓" else "✗"

            sb.appendLine("| $context | $aggregate | ${check(hasOcc)} | ${check(hasIdempotency)} | ${check(hasOutbox)} | ${check(hasProtocolCheck)} | ${check(hasDeps)} | ${check(hasLease)} |")
        }

        // Add read-only contexts
        sb.appendLine("| library | _(read-only)_ | n/a | n/a | n/a | n/a | n/a | n/a |")
        sb.appendLine("| ml | _(read-only)_ | n/a | n/a | n/a | n/a | n/a | n/a |")
        sb.appendLine("| karaoke | _(read-only)_ | n/a | n/a | n/a | n/a | n/a | n/a |")

        sb.appendLine()
        sb.appendLine("## Legend")
        sb.appendLine()
        sb.appendLine("- **OCC**: Optimistic Concurrency Control via `expectedVersion`")
        sb.appendLine("- **Idempotency**: Uses `IdempotencyStore` for replay protection")
        sb.appendLine("- **Outbox**: Enqueues `DomainNotification` in same transaction")
        sb.appendLine("- **Protocol Check**: Validates command against `ProtocolCapabilities`")
        sb.appendLine("- **Deps Pattern**: Cross-context data loaded before handler call")
        sb.appendLine("- **Lease**: Single-writer lease enforcement")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString())

        println("Contract matrix generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateDependencyGraph") {
    group = "documentation"
    description = "Generates Mermaid dependency graph from Gradle module dependencies"

    val outputFile = file("docs/generated/dependency-graph.md")

    doLast {
        val sb = StringBuilder()
        sb.appendLine("# Module Dependency Graph")
        sb.appendLine()
        sb.appendLine("Auto-generated from build files. Do not edit manually.")
        sb.appendLine()
        sb.appendLine("```mermaid")
        sb.appendLine("graph TD")

        // Map short names for readability
        fun shortName(path: String): String = path.removePrefix(":").replace(":", "/")

        subprojects.filter { it.childProjects.isEmpty() }.forEach { proj ->
            val from = shortName(proj.path)
            proj.configurations.findByName("implementation")?.dependencies?.forEach { dep ->
                if (dep is org.gradle.api.artifacts.ProjectDependency) {
                    val to = shortName(dep.dependencyProject.path)
                    sb.appendLine("    ${from.replace("/", "_").replace("-", "_")} --> ${to.replace("/", "_").replace("-", "_")}")
                }
            }
            proj.configurations.findByName("api")?.dependencies?.forEach { dep ->
                if (dep is org.gradle.api.artifacts.ProjectDependency) {
                    val to = shortName(dep.dependencyProject.path)
                    sb.appendLine("    ${from.replace("/", "_").replace("-", "_")} ==> ${to.replace("/", "_").replace("-", "_")}")
                }
            }
        }

        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("Solid arrows (`-->`) = `implementation` dependency")
        sb.appendLine()
        sb.appendLine("Double arrows (`==>`) = `api` (transitive) dependency")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString())

        println("Dependency graph generated: ${outputFile.absolutePath}")
    }
}
