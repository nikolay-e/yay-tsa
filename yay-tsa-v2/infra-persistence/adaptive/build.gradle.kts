plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:adaptive"))
    implementation(project(":core-application:shared"))
    implementation(project(":core-application:adaptive"))
    implementation(project(":infra-persistence:shared"))

    testRuntimeOnly(kotlin("reflect"))
}
