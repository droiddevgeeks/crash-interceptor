import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.droiddevgeeks.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.droiddevgeeks.sample"
        minSdk = 23
        targetSdk = 36
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

    // Import the Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    // Crashlytics installs its OWN Thread.UncaughtExceptionHandler — this is the real
    // third-party crash reporter that crashsink decorates and always delegates to.
    implementation("com.google.firebase:firebase-crashlytics")
}
