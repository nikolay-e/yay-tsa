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
    implementation(project(":core-domain:auth"))
    implementation(project(":core-domain:library"))
    implementation(project(":core-domain:playback"))
    implementation(project(":core-domain:playlists"))
    implementation(project(":core-domain:preferences"))
    implementation(project(":core-domain:adaptive"))
    implementation(project(":core-domain:ml"))
    implementation(project(":core-domain:karaoke"))
    implementation(project(":core-application:shared"))
    implementation(project(":core-application:auth"))
    implementation(project(":core-application:library"))
    implementation(project(":core-application:playback"))
    implementation(project(":core-application:playlists"))
    implementation(project(":core-application:preferences"))
    implementation(project(":core-application:adaptive"))
    implementation(project(":core-application:ml"))
    implementation(project(":core-application:karaoke"))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
