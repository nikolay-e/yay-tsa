plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
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
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:ml"))
    implementation(project(":core-application:ml"))
    implementation(project(":infra-persistence:shared"))
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(kotlin("reflect"))
}
