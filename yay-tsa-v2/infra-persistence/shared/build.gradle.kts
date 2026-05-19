plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-application:shared"))
    implementation(libs.jackson.module.kotlin)
}
