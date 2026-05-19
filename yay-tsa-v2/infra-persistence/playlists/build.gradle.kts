plugins {
    id("yaytsa.infra-persistence")
}

dependencies {
    implementation(project(":core-domain:playlists"))
    implementation(project(":core-application:playlists"))
    implementation(project(":core-application:shared"))
    implementation(project(":infra-persistence:shared"))
    implementation(kotlin("reflect"))
}
