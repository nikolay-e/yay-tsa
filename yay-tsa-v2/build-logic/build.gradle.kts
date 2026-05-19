plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.kotlin.noarg)
    implementation(libs.spring.dependency.management.plugin)
}

spotless {
    kotlin {
        ktlint()
        target("src/**/*.kt", "src/**/*.kts")
        targetExclude("build/**")
    }
    kotlinGradle {
        ktlint()
        target("*.gradle.kts")
        targetExclude("build/**")
    }
}
