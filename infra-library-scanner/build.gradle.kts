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
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:library"))
    implementation(project(":core-application:shared"))
    implementation(project(":core-application:library"))
    implementation(project(":infra-persistence:library"))
    implementation(libs.spring.boot.starter.data.jpa)
    implementation("net.jthink:jaudiotagger:3.0.1")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
