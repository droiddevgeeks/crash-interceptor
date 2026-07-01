// Root project: declares plugin versions once, applies nothing here.
// Subprojects (:crashsink, :sample) apply the plugins they need without re-stating versions.
plugins {
    id("com.android.library") version "8.11.1" apply false
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
