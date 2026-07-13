plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:shared"))
    implementation(project(":core-domain:library"))
    implementation(project(":core-application:library"))
    implementation(project(":infra-persistence:shared"))

    testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect")
}
