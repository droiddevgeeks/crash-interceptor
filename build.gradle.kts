// Root project: declares plugin versions once, applies nothing here.
// Subprojects (:crashsink, :sample) apply the plugins they need without re-stating versions.
plugins {
    id("com.android.library") version "8.13.2" apply false
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Add the dependency for the Google services Gradle plugin
    id("com.google.gms.google-services") version "4.5.0" apply false
}
