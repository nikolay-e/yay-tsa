plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:ml"))
    implementation(project(":core-application:ml"))
    implementation(project(":infra-persistence:shared"))

    testRuntimeOnly(kotlin("reflect"))
}
