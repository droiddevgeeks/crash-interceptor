plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    // Compile with a pinned JDK regardless of the developer's local JVM, so the build is
    // reproducible and insulated from whatever Java a contributor happens to have installed.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Emit Java 8 bytecode (portable back to the cf-analytics Android module: no var,
    // no records, no switch-expressions). --release validates against the Java 8 API and,
    // unlike source/target 8, does not trigger the "obsolete source value 8" warning on JDK 17.
    options.release.set(8)
}

dependencies {
    // org.json is provided by the Android platform at runtime in the real module;
    // in this standalone JVM project it must be a real compile+runtime dependency.
    implementation("org.json:json:20231013")

    testImplementation("junit:junit:4.13.2")
    // Mockito 5.x: inline mock-maker (mock final classes) is the default; mockito-inline retired.
    testImplementation("org.mockito:mockito-core:5.14.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
