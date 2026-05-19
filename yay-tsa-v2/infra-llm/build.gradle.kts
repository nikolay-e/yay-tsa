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
    implementation(project(":core-domain:adaptive"))
    implementation(project(":core-domain:library"))
    implementation(project(":core-domain:ml"))
    implementation(project(":core-domain:preferences"))
    implementation(project(":core-application:shared"))
    implementation(project(":core-application:adaptive"))
    implementation(project(":core-application:preferences"))
    implementation(project(":core-application:library"))
    implementation(project(":core-application:ml"))
    // Workers bypass core-domain and write directly to their schemas (per manifesto).
    // LlmOrchestrator writes audit-trail rows to core_v2_adaptive.llm_decisions.
    implementation(project(":infra-persistence:adaptive"))
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.anthropic.sdk)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
