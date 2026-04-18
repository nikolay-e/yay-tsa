plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    // Domain
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:auth"))
    implementation(project(":core-domain:library"))
    implementation(project(":core-domain:playback"))
    implementation(project(":core-domain:adaptive"))
    implementation(project(":core-domain:preferences"))
    implementation(project(":core-domain:playlists"))
    implementation(project(":core-domain:ml"))
    implementation(project(":core-domain:karaoke"))

    // Application
    implementation(project(":core-application:shared"))
    implementation(project(":core-application:auth"))
    implementation(project(":core-application:library"))
    implementation(project(":core-application:playback"))
    implementation(project(":core-application:adaptive"))
    implementation(project(":core-application:preferences"))
    implementation(project(":core-application:playlists"))
    implementation(project(":core-application:ml"))
    implementation(project(":core-application:karaoke"))

    // Persistence
    implementation(project(":infra-persistence:shared"))
    implementation(project(":infra-persistence:auth"))
    implementation(project(":infra-persistence:library"))
    implementation(project(":infra-persistence:playback"))
    implementation(project(":infra-persistence:adaptive"))
    implementation(project(":infra-persistence:preferences"))
    implementation(project(":infra-persistence:playlists"))
    implementation(project(":infra-persistence:ml"))
    implementation(project(":infra-persistence:karaoke"))

    // Infrastructure
    implementation(project(":infra-media"))
    implementation(project(":infra-notifications"))
    implementation(project(":infra-library-scanner"))
    implementation(project(":infra-ml-worker"))
    implementation(project(":infra-karaoke-worker"))
    implementation(project(":infra-llm"))

    // Adapters
    implementation(project(":adapter-opensubsonic"))
    implementation(project(":adapter-jellyfin"))
    implementation(project(":adapter-mcp"))
    implementation(project(":adapter-mpd"))

    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.springdoc.openapi.starter)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(project(":core-testkit"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.konsist)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
