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
    // Worker writes directly to its schema via infra-persistence; core-domain:shared is
    // constants-only (AudiobookGenres), not a command-path dependency.
    implementation(project(":core-domain:shared"))
    implementation(project(":infra-persistence:ml"))
    implementation(project(":infra-persistence:library"))
    implementation(libs.spring.boot.starter.data.jpa)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.testcontainers.postgresql)
}
