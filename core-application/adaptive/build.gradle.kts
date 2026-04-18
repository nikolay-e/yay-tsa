dependencies {
    implementation(project(":core-domain:adaptive"))
    implementation(project(":core-application:shared"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
