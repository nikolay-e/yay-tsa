dependencies {
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:library"))
    implementation(project(":core-domain:ml"))
    implementation(project(":core-domain:preferences"))
    implementation(project(":core-application:shared"))
    implementation(project(":core-application:library"))
    implementation(project(":core-application:ml"))
    implementation(project(":core-application:playback"))
    implementation(project(":core-application:preferences"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.test)
}
