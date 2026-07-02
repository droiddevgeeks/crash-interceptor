pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Resolves the published crashsink artifact for the :sample app.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "crash-interceptor"
include(":crashsink")
include(":sample")
