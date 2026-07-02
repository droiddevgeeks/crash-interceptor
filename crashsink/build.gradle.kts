import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Maven coordinates. On JitPack the served group is always com.github.<owner>.<repo> and the
// artifactId is this module's name (crashsink) — these values are what a local
// publishToMavenLocal smoke test produces and what a non-JitPack Maven publish would use.
// Override the version at publish time with -Pversion=<x> (JitPack builds the git tag).
group = "com.droiddevgeeks"
version = (findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "0.1.0"

android {
    namespace = "com.droiddevgeeks.crashsink"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // Keep rules shipped to the consuming app's R8 run.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Libraries usually ship un-minified and let the consuming app's R8 do the work.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Publish only the release variant. withSourcesJar ships sources so consumers get Kotlin
    // param names / KDoc in their IDE. AGP creates the `release` software component from this,
    // which the publication below consumes (only available after evaluation).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        // Java 8 bytecode: broad Android device reach, no desugaring required.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            // Match the Java 8 bytecode target so Kotlin output stays desugaring-free.
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    testOptions {
        unitTests {
            // android.util.Log etc. return defaults instead of throwing in host unit tests.
            isReturnDefaultValues = true
            all {
                // Mockito inline mock-maker (mocks final classes) on JDK 17.
                it.jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
            }
        }
    }
}

dependencies {
    // org.json is provided by the Android platform at runtime; on the host JVM unit-test
    // classpath we supply a real impl (the platform's is a throwing stub under unit tests).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.json:json:20260522")
}

// AGP builds the `release` component only after the project is evaluated, so bind the
// publication inside afterEvaluate. No repositories {} block: JitPack publishes to its own
// local Maven when building the git tag; a plain `./gradlew :crashsink:publishToMavenLocal`
// installs the same artifact into ~/.m2 for local verification.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "crashsink"
            }
        }
    }
}
