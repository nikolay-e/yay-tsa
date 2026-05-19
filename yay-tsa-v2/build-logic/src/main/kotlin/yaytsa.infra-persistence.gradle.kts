plugins {
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("io.spring.dependency-management")
}

// TODO: catalog wiring — versions duplicated from gradle/libs.versions.toml until precompiled-script catalog access is plumbed.
val springBootVersion = "3.4.4"
val flywayVersion = "11.3.1"
val postgresqlVersion = "42.7.4"
val testcontainersVersion = "1.20.4"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    }
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
    "implementation"("org.flywaydb:flyway-core:$flywayVersion")
    "implementation"("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    "runtimeOnly"("org.postgresql:postgresql:$postgresqlVersion")

    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.testcontainers:junit-jupiter:$testcontainersVersion")
    "testImplementation"("org.testcontainers:postgresql:$testcontainersVersion")
    "testImplementation"("org.jetbrains.kotlin:kotlin-test")
    "testImplementation"(project(":core-testkit"))
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    forkEvery = 0
    systemProperty("testcontainers.reuse.enable", "true")
    environment("TESTCONTAINERS_REUSE_ENABLE", "true")
}
