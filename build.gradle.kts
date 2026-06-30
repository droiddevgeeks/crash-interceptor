plugins {
    id("com.android.library") version "8.8.2"
}

android {
    namespace = "com.droiddevgeeks.crashsink"
    compileSdk = 35

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

    compileOptions {
        // Java 8 bytecode: broad Android device reach, no desugaring required.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.json:json:20231013")
}
