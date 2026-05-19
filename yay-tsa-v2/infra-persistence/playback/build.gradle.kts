plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:playback"))
    implementation(project(":core-application:playback"))
    implementation(project(":core-application:shared"))
    implementation(project(":infra-persistence:shared"))
    implementation(kotlin("reflect"))
}
