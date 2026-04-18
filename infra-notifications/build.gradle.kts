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
    implementation(project(":core-application:auth"))
    implementation(project(":core-domain:shared"))
    implementation(project(":infra-persistence:shared"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.websocket)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
