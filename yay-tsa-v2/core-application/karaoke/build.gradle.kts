dependencies {
    implementation(project(":core-domain:karaoke"))
    implementation(project(":core-application:shared"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
