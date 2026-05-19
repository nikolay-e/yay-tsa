plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:preferences"))
    implementation(project(":core-application:preferences"))
    implementation(project(":core-application:shared"))
    implementation(project(":infra-persistence:shared"))
    implementation(kotlin("reflect"))
}
