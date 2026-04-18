plugins {
    `java-library`
}

dependencies {
    api(project(":core-domain:shared"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlin.test)
}
