plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:karaoke"))
    implementation(project(":core-application:karaoke"))
    implementation(project(":infra-persistence:shared"))

    testRuntimeOnly(kotlin("reflect"))
}
