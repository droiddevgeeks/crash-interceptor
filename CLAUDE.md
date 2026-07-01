# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`crashsink` is a small Android library (published as an `.aar`) that installs itself as a
**decorator** in the single `Thread.setDefaultUncaughtExceptionHandler` slot. It captures only
the crashes attributable to *your* code (matched by a caller-supplied package prefix) and
**always delegates** the crash downstream, so a host app's Crashlytics/Sentry/Bugsnag still
sees everything. It is a guest-SDK crash reporter, not an app-owner one.

## Build & test

This is a **two-module Gradle build** — the root is not itself a module, so task names must be
module-qualified (`./gradlew testDebugUnitTest` alone will not work):

```bash
./gradlew :crashsink:testDebugUnitTest      # 55 host-JVM unit tests (Kotlin + 1 Java interop test)
./gradlew :crashsink:assembleRelease        # -> crashsink/build/outputs/aar/crashsink-release.aar
./gradlew :sample:assembleDebug             # -> sample/build/outputs/apk/debug/sample-debug.apk

# Run a single test class or method:
./gradlew :crashsink:testDebugUnitTest --tests "com.droiddevgeeks.crashsink.CrashProcessorTest"
./gradlew :crashsink:testDebugUnitTest --tests "*.CrashProcessorTest.payloadIsRedacted"
```

Tests run entirely on the host JVM (JUnit4 + mockito-core, `testOptions.unitTests.isReturnDefaultValues = true`
so `android.*` calls return defaults instead of throwing). No device/emulator needed. Requires
the Android SDK (`ANDROID_HOME`) with API 36 installed. `--add-opens java.base/java.lang=ALL-UNNAMED`
is already wired for mockito's inline mock-maker (it mocks final Kotlin classes).

Toolchain is **pinned to exact versions** (never use `+`/dynamic ranges): Gradle 8.14.3,
AGP 8.13.2, Kotlin 2.2.20. Plugin versions live once in the root `build.gradle.kts`
(`apply false`); subprojects apply without restating versions.

## Architecture — the crash pipeline

Two phases, connected by the filesystem (write-at-crash-time, deliver-on-next-launch):

```
CRASH (dying thread):  CrashInterceptor → CrashAttributor.isOurs? → yes → CrashProcessor.persistBlocking
                       → build payload (writer thread) → Redactor.scrub → CrashFileStore.writeAtomic
                       → ALWAYS delegate to previous handler (finally)
NEXT LAUNCH:           CrashReporter.install → CrashIngestor.flushAsync → read files
                       → CrashSink.submit → delete file on success (keep for retry on failure)
```

`CrashReporter` is the public facade that wires everything (`create` factories → `install` →
`startCapturing`/`stopCapturing` → `shutdown`). `CrashHandlerManager` owns install + idempotent
`reassert` (self-heals if another library displaces us in the handler chain).

**Attribution** (`CrashAttributor` + `CrashFrames`): walk to the deepest `getCause()` (bounded to
guard cycles), skip framework frames (`java.`/`kotlin.`/`android.`/…), and check whether the first
remaining application frame's class name **starts with** the configured `ownedPrefix`. A crash that
merely passes *through* your code (host callback you invoked that threw) is correctly left to the host.

## Invariants — do not break these

- **Crash-path safety is sacred.** The whole `CrashInterceptor.uncaughtException` body is wrapped in
  `catch (Throwable)` and **always** delegates to the previous handler in `finally`. Nothing on the
  dying thread may throw or block unboundedly. The dying thread only does `latch.await(flushTimeoutMillis)`;
  payload building + disk I/O run on the `crash-writer` daemon executor. Never add allocation/I/O/locks
  to the dying-thread path.
- **minSdk 21, no desugaring.** Use only `java.io` + core APIs available since API 21. Specifically
  banned: `java.nio.file` (API 26+), `List.sort`/`Comparator.comparing*` (API 24+ — use
  `Collections.sort` with an explicit comparator), and `Thread.threadId()` (API 36+ — keep the
  deprecated `Thread.getId()`; its deprecation is suppressed on purpose in `CrashProcessor`).
- **Java 8 bytecode** (`jvmTarget = 1.8`, `sourceCompatibility/targetCompatibility = 1.8`).
- **No new runtime dependencies.** `org.json` is provided by the Android platform at runtime; it is a
  *test-only* dependency (the platform's `org.json` is a throwing stub under host unit tests).

## Kotlin ↔ Java interop contract

The library is Kotlin but its public API must stay callable from Java. When editing public API, preserve:
- `@JvmStatic` on companion/object factories so Java calls `CrashReporter.create(...)`,
  `CrashProcessor.buildPayloadJson(...)`, `AndroidDeviceMetadata.collect(...)` without `.Companion`.
- `@JvmField` on `DeviceMetadata` properties (Java reads `metadata.osVersion` as a field).
- `CrashSink` is a `fun interface` (SAM) — usable as a lambda from both Kotlin and Java.
- Kotlin has no package-private. `CrashProcessor.buildPayloadJson` and the `CrashReporter` wiring
  constructor are deliberately **public** (documented "visible for testing") because `internal` gets
  name-mangled and is unusable from Java tests. `CrashLogger` and `CrashFrames` are `internal object`s
  (no external caller) — keep them internal.
- `crashsink/src/test/java/.../JavaInteropTest.java` is the permanent, CI-enforced Java-callability
  guard. If you change public API, keep this compiling and passing.

## Modules

- `:crashsink` — the library (`com.android.library`). Sources in `src/main/kotlin`, tests in
  `src/test/kotlin` (+ the one `src/test/java` interop test). Ships `consumer-rules.pro`
  (bundled into the AAR) and `proguard-rules.pro`.
- `:sample` — a runnable demo app (`com.android.application`, plain `Activity`, no AppCompat) that
  consumes `:crashsink` via a project dependency. `com.droiddevgeeks.fakesdk.FakeSdk` is a pretend
  third-party SDK; `JavaInteropDemo.java` drives the API from Java.

## R8/obfuscation gotcha (consumers)

Attribution matches on package name. If a consumer obfuscates their SDK package, runtime frames become
`a.b.c`, the `ownedPrefix` stops matching, and crashes are **silently not captured**. `consumer-rules.pro`
ships a fill-in `-keeppackagenames` + `-keepattributes SourceFile,LineNumberTable` template (see README).
