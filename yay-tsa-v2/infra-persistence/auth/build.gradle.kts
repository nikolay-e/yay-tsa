plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:auth"))
    implementation(project(":core-application:auth"))
    implementation(project(":infra-persistence:shared"))
    implementation(kotlin("reflect"))
}
