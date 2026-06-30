import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.droiddevgeeks.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droiddevgeeks.sample"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
}

dependencies {
    // Sibling-module dependency: robust build ordering, identical runtime behavior to the
    // published .aar (the library carries zero runtime deps — org.json is platform-provided).
    implementation(project(":crashsink"))
}
