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
./gradlew :crashsink:testDebugUnitTest      # 64 host-JVM unit tests (Kotlin + 1 Java interop guard)
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

## Public API surface & Kotlin ↔ Java interop contract

The public API is deliberately **minimal** — only the consumer contract is `public`; everything else
is `internal`. The entire public surface is:
- **`CrashReporter`** — the facade. A single `create(...)` factory (`@JvmStatic` + `@JvmOverloads`:
  `create(context, sink, ownedPrefix, fileCap = DEFAULT_FILE_CAP, flushTimeoutMillis =
  DEFAULT_FLUSH_TIMEOUT_MS)` — Kotlin omits the trailing defaults, `@JvmOverloads` gives Java the
  same), the `DEFAULT_*` constants, and the `install`/`startCapturing`/`stopCapturing`/`shutdown`
  lifecycle. Its wiring **constructor is `internal`** (test-only injection; it also necessarily takes
  internal collaborator types). The crash dir is derived internally (`crashDirFor`, `internal`) —
  consumers don't pick it.
- **`CrashSink`** — a `fun interface` (SAM), usable as a lambda from Kotlin and Java. Consumers
  implement it. Its callback carries only `String`/primitive params (device/app metadata reaches it
  as the `contexts` JSON string), so no other type needs to be public.

Everything else is `internal` implementation and must stay that way: `CrashInterceptor`,
`CrashAttributor`, `CrashHandlerManager`, `CrashProcessor` (incl. `buildPayloadJson`),
`CrashFileStore`, `CrashIngestor`, `Redactor`, `AndroidDeviceMetadata`, `DeviceMetadata`,
`CrashLogger`, `CrashFrames`. Kotlin has no package-private; these are covered by Kotlin unit tests
in the same module (which can see `internal`). A `public` function may not expose an `internal` type
in its signature — that's why the wiring constructor is internal.

`crashsink/src/test/java/.../JavaInteropTest.java` is the permanent, CI-enforced guard that the
**public** API stays Java-callable (SAM `CrashSink`, the `create` factory — both the defaults and
explicit `@JvmOverloads` forms — the `DEFAULT_*` constants, and the lifecycle). If you change the
public API, keep it compiling and passing. Do **not**
add coverage of internal types there — internal is name-mangled/unusable from Java; test internals in
Kotlin instead.

## Modules

- `:crashsink` — the library (`com.android.library`). Sources in `src/main/kotlin`, tests in
  `src/test/kotlin` (+ the one `src/test/java` interop test). Ships `consumer-rules.pro`
  (bundled into the AAR) and `proguard-rules.pro`.
- `:sample` — a runnable demo app (`com.android.application`, plain `Activity`, no AppCompat) that
  consumes the **published JitPack artifact** (`com.github.droiddevgeeks:crash-interceptor:<tag>`),
  not `:crashsink` via a project dependency — the project dep is left commented out in
  `sample/build.gradle.kts` for easy switch-back during local library dev. This dogfoods the exact
  AAR external consumers get, but means local `:crashsink` changes won't reach the sample until
  republished (bump + tag). `com.droiddevgeeks.fakesdk.FakeSdk` is a pretend third-party SDK;
  `JavaInteropDemo.java` drives the API from Java.

## Publishing (JitPack)

The library is published via **JitPack**, which builds from a Git tag — there is no upload step and
no artifact registry credentials. Release flow: `git tag <x> && git push origin <x>` (GitHub
**auto-creates a Release** on tag push; use `gh release edit <x>` to fill in notes, not
`gh release create` — that 422s on the existing release). Verify the build is green at
`jitpack.io/#droiddevgeeks/crash-interceptor` before announcing a version.

- **`:crashsink/build.gradle.kts`** applies `maven-publish` with an AGP `singleVariant("release")
  { withSourcesJar() }` block and an `afterEvaluate` publication (the `release` software component
  only exists post-evaluation). There is **no `repositories {}` block** — JitPack owns the
  destination; `./gradlew :crashsink:publishToMavenLocal` installs the same artifact to `~/.m2` for
  local smoke-testing.
- **`jitpack.yml`** (repo root) pins **JDK 17** (AGP 8.13 needs it) and runs only
  `./gradlew :crashsink:publishToMavenLocal`, so `:sample`'s `google-services` task never executes —
  a missing (untracked) `google-services.json` on a fresh JitPack clone therefore can't break the
  release build.
- **Coordinate quirk:** because only `:crashsink` publishes, JitPack collapses it to the
  **repo-level** coordinate `com.github.droiddevgeeks:crash-interceptor:<tag>` (served AAR is named
  `crash-interceptor-<tag>.aar`, contents unchanged). It is **not** the multi-module
  `com.github.<owner>.<repo>:<module>:<tag>` form — that only appears if a *second* module ever
  publishes, which would silently change the coordinate. Keep `:crashsink` the sole published module.
- **`version`** in `crashsink/build.gradle.kts` defaults to `0.1.0` (override with `-Pversion=`), but
  JitPack ignores it and serves under the **Git tag** — the tag is the source of truth.
- Tags are effectively **immutable** on JitPack (a moved tag may keep serving a stale cached build):
  never move a tag; always bump.

A long-form write-up of the design lives at `docs/medium-article.md` (a Medium draft).

## R8/obfuscation gotcha (consumers)

Attribution matches on package name. If a consumer obfuscates their SDK package, runtime frames become
`a.b.c`, the `ownedPrefix` stops matching, and crashes are **silently not captured**. `consumer-rules.pro`
ships a fill-in `-keeppackagenames` + `-keepattributes SourceFile,LineNumberTable` template (see README).
