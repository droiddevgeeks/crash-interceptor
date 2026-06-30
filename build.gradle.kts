plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    // Keep Java 8 level so code stays portable back to the cf-analytics Android module
    // (no var, no records, no switch-expressions).
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    // org.json is provided by the Android platform at runtime in the real module;
    // in this standalone JVM project it must be a real compile+runtime dependency.
    implementation("org.json:json:20231013")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-inline:4.11.0")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
