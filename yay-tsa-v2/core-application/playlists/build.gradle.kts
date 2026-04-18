dependencies {
    implementation(project(":core-domain:playlists"))
    implementation(project(":core-application:shared"))

    testImplementation(project(":core-testkit"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
