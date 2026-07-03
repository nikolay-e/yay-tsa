plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(
            libs.spring.boot.bom
                .get()
                .toString(),
        )
    }
}

dependencies {
    implementation(project(":core-application:shared"))
    implementation(project(":core-application:library"))
    implementation(project(":core-domain:shared"))
    implementation(project(":infra-persistence:shared"))
    implementation(project(":infra-persistence:playback"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.module.kotlin)

    testImplementation(project(":core-testkit"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    systemProperty("testcontainers.reuse.enable", "true")
}
