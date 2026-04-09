import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yaytsa.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yaytsa.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "2.0"
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val signingFile = rootProject.file("signing.properties")
            if (signingFile.exists()) props.load(signingFile.inputStream())
            storeFile = file(props.getProperty("STORE_FILE", "../release.keystore"))
            storePassword = props.getProperty("STORE_PASSWORD", "")
            keyAlias = props.getProperty("KEY_ALIAS", "yaytsa")
            keyPassword = props.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:${property("coreKtxVersion")}")
    implementation("androidx.appcompat:appcompat:${property("appcompatVersion")}")
    implementation("com.google.android.material:material:${property("materialVersion")}")
    implementation("androidx.constraintlayout:constraintlayout:${property("constraintlayoutVersion")}")
    implementation("androidx.security:security-crypto:${property("securityCryptoVersion")}")
    implementation("androidx.media:media:${property("mediaVersion")}")

    testImplementation("junit:junit:${property("junitVersion")}")
    testImplementation("org.json:json:${property("jsonVersion")}")
}
