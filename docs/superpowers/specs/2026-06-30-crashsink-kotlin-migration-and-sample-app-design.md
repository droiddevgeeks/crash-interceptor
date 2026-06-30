# crashsink — Kotlin Migration + Sample App — Design

**Date:** 2026-06-30
**Status:** Approved (pending spec review)

## Goal

Migrate the `crashsink` crash-interceptor library from Java to Kotlin (primary
language going forward) **without breaking Java callers**, and add a runnable
sample Android app that consumes the built `.aar` and demonstrates the core
behavior: capture only the SDK's own crashes, delegate everything else.

## Non-goals

- No behavioral change to the library. Same attribution, same persistence,
  same crash-path guarantees. This is a language migration, not a redesign.
- No new public API beyond `@JvmOverloads`-driven default-argument convenience
  for Kotlin callers (additive, not breaking).
- No Compose. The sample is a test harness, not a product.
- No new runtime dependencies. `org.json` stays platform-provided.

## Constraints (carried from the existing project)

- **minSdk 21 → compileSdk 35.** `java.io` only; no `java.nio.file`
  (API 26+, not desugared), no `List.sort`/`Comparator.comparingLong` (API 24).
- **Java 8 bytecode** (`jvmTarget = 1.8`), no core-library desugaring.
- **Exact dependency versions only — never `+` / dynamic ranges.**
- Package stays `com.droiddevgeeks.crashsink`.
- Toolchain pinned: **AGP 8.8.2 / Gradle 8.10.2 / Kotlin 2.0.21.**
- Public API must stay **byte-compatible for existing Java callers.**

---

## Architecture

### Part A — Multi-module restructure

The library currently *is* the root Gradle project. To host a sample app it
moves into a `:crashsink` subproject:

```
crash-interceptor/
├── settings.gradle.kts          include(":crashsink", ":sample")
├── build.gradle.kts             root: plugins declared `apply false`
├── gradle/ gradlew gradlew.bat gradle.properties   (stay at root)
├── crashsink/
│   ├── build.gradle.kts         com.android.library + org.jetbrains.kotlin.android
│   ├── consumer-rules.pro
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/kotlin/com/droiddevgeeks/crashsink/*.kt
│       └── test/kotlin/com/droiddevgeeks/crashsink/*.kt
└── sample/
    ├── build.gradle.kts         com.android.application + org.jetbrains.kotlin.android
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/droiddevgeeks/sample/*.kt
        ├── kotlin/com/droiddevgeeks/fakesdk/*.kt
        ├── java/com/droiddevgeeks/sample/JavaInteropDemo.java
        └── res/layout/activity_main.xml
```

Files move via `git mv` to preserve history. `LICENSE`, `NOTICE`, `README.md`
stay at repo root. `.gitignore` updated for the new `*/build/` paths.

### Part B — Kotlin migration, the interop contract

Public API stays callable from Java exactly as today. Per-class mapping:

| Java class | Kotlin shape | Interop note |
|---|---|---|
| `CrashSink` (interface) | `fun interface CrashSink { fun submit(token: String?, exceptionValues: String, level: String, culprit: String, timestamp: Long, contexts: String) }` | SAM: Java lambda `(...) -> {}` and Kotlin trailing lambda both work |
| `CrashReporter` | `class` + `companion object { @JvmStatic @JvmOverloads fun create(...) }` | Java: `CrashReporter.create(...)`. `@JvmOverloads` exposes `fileCap`/`flushTimeoutMs` defaults to Kotlin callers |
| `DeviceMetadata` | `class` with `@JvmField val` properties | preserves `metadata.osVersion` field access |
| `AndroidDeviceMetadata` | `object { @JvmStatic fun collect(context): DeviceMetadata }` | |
| `CrashLogger` | `internal object` with `fun e(tag, message)` | call sites become `CrashLogger.e(...)` |
| `CrashFrames` | `internal object` + `const val MAX_CAUSE_DEPTH = 50` | shared frame classification |
| `Redactor` | `class` — `fun scrub(message: String?): String?` | nullability made explicit |
| `CrashAttributor` | `class(ownedPrefix: String)` — `fun isOurs(t: Throwable?): Boolean` | |
| `CrashFileStore` | `class(dir: File, maxFiles: Int)` | `renameTo` (atomic), `Collections.sort`+`Long.compare` preserved — no API-24 idioms |
| `CrashProcessor` | `class` + `companion object { fun buildPayloadJson(...) }` | `buildPayloadJson` stays internally testable |
| `CrashInterceptor` | `class : Thread.UncaughtExceptionHandler` | `try/catch(t: Throwable)/finally` delegate preserved |
| `CrashHandlerManager` | `class` — `install` / `reassert` / `getInstalled` | |
| `CrashIngestor` | `class` — `flushAsync` / `ingestOne` / `readUtf8` | `FileInputStream` read preserved (no `java.nio.file`) |

**Crash-path safety is invariant.** No Kotlin idiom is allowed to add hot-path
allocation on the dying thread. The dying thread only does `latch.await(...)`;
payload building runs on the writer executor. `ExecutorService.submit { }`,
`CountDownLatch`, `AtomicLong`, and the `catch (Throwable)` + `finally` delegate
all translate 1:1.

**Build (`crashsink/build.gradle.kts`):**
- plugins: `com.android.library` 8.8.2, `org.jetbrains.kotlin.android` 2.0.21
- `compilerOptions { jvmTarget = JvmTarget.JVM_1_8 }`
- `compileOptions` Java 8 source/target retained
- `consumerProguardFiles("consumer-rules.pro")`, release `proguardFiles(...)` retained
- `testOptions.unitTests { isReturnDefaultValues = true; all { jvmArgs("--add-opens","java.base/java.lang=ALL-UNNAMED") } }` retained
- test deps unchanged: `junit:junit:4.13.2`, `org.mockito:mockito-core:5.14.2`, `org.json:json:20231013`

**Mocking final Kotlin classes:** Kotlin classes are `final` by default.
mockito-core 5.x uses the inline mock-maker by default, which mocks final
classes; the existing `--add-opens` jvmArg stays. Classes are **not** opened
solely for tests.

### Part C — Migration sequencing (never lose a green bar)

1. **Restructure to multi-module** with library code still in Java. Run the
   Java test suite — green. (Proves the move didn't break anything.)
2. **Add Kotlin plugin**, keep Java sources compiling alongside. Green.
3. **Convert production classes to Kotlin one at a time**, leaving the Java
   tests untouched. After each class: run the Java suite — green. The Java
   tests are the live interop oracle through this whole phase.
4. **Convert the test suite to Kotlin** (JUnit4, identical assertions) only
   once all production code is Kotlin and green. 50 tests → 50 tests.
5. **Build the sample app** against the release `.aar`; manual runtime proof.

### Part D — Sample app behavior

`:sample` — `com.android.application`, Kotlin + one Java class, plain Views.

- `applicationId = "com.droiddevgeeks.sample"`, `minSdk 21`, `compileSdk 35`,
  `targetSdk 35`.
- Two packages: `com.droiddevgeeks.sample` (host) and
  `com.droiddevgeeks.fakesdk` (the pretend SDK). The app configures
  `ownedPrefix = "com.droiddevgeeks.fakesdk."`.
- **On startup (`MainActivity.onCreate`):**
  1. Install a demo *host* `UncaughtExceptionHandler` first — it logs
     `"HOST HANDLER saw it"` (via `android.util.Log`) and re-throws to the
     system. This proves crashsink **decorates** rather than replaces.
  2. `CrashReporter.create(this, fileCap=20, flushTimeoutMs=1000L, sink, ownedPrefix).install()`.
     The `CrashSink` lambda appends each ingested crash to an on-screen
     `TextView` log and to logcat.
  3. `startCapturing("sample-session")`.
  4. Crashes captured on the **previous** run are ingested during `install()`
     and shown on screen — demonstrating write-now/send-on-next-launch.
- **Buttons (`activity_main.xml`, programmatic listeners):**
  - **Crash in SDK code** → calls `com.droiddevgeeks.fakesdk.FakeSdk.boom()`
    which throws → top app frame is `com.droiddevgeeks.fakesdk.*` → **captured**
    by crashsink; surfaced on next launch.
  - **Crash in host code** → throws directly from a
    `com.droiddevgeeks.sample.*` method → top app frame is the host → **not
    captured** → delegated to the host handler ("HOST HANDLER saw it").
  - **Java interop** → `JavaInteropDemo.run(context)` (Java) calls
    `CrashReporter.create(...)` / `startCapturing` / `stopCapturing` → proves
    the Kotlin API is callable from Java, compiled and at runtime.
- **AAR consumption:** `implementation(files("${rootDir}/crashsink/build/outputs/aar/crashsink-release.aar"))`.
  A task dependency wires `:sample`'s `preBuild` (or compile task) to depend on
  `:crashsink:assembleRelease`, so building the app first builds the AAR.
  Feasible because the library has **zero** runtime deps (`org.json` is on
  the platform), so `files(...)` needs no transitive resolution.

## Data flow (unchanged from current library)

```
crash → CrashInterceptor → (CrashAttributor: ours?) → yes → CrashProcessor
        → Redactor.scrub → CrashFileStore.writeAtomic (bounded by latch)
        → always delegate to previous handler (finally)

next launch → CrashReporter.install → CrashIngestor.flushAsync
            → read files → CrashSink.submit → delete on success
```

## Error handling (unchanged)

- Whole crash path wrapped in `catch (Throwable)`; always delegates in
  `finally`. crashsink can never become a second crash.
- Bounded by `flushTimeoutMillis` (default 1s); no network/db/lock/fsync on
  crash path.
- Ingestion: parse failures drop the poison file; sink failures keep the file
  for retry.

## Testing

- **Unit (host JVM):** the migrated Kotlin test suite — 50 tests, JUnit4 +
  mockito-core, `returnDefaultValues` for `android.*`. Covers redaction,
  attribution, file store atomicity/cap, processor dedupe + bounded write +
  payload JSON, interceptor delegate-always, handler manager idempotent
  re-assert, ingestor retry semantics, reporter wiring, device-metadata
  collection.
- **Runtime (sample app):** manual verification of the three buttons and the
  next-launch ingestion display; the Java interop button proves Java callers
  work at runtime.

## Risks

- **Kotlin 2.0.21 ↔ AGP 8.8.2 / Gradle 8.10.2 pairing** — verified at first
  build; bumped to the nearest compatible exact version if the toolchain
  disagrees.
- **`files(.aar)` build ordering** — solved by the explicit task dependency;
  fallback is `project(":crashsink")` with a documented note if `files(...)`
  proves fragile.
- **mockito + final Kotlin classes** — covered by the inline mock-maker
  (mockito-core 5.x default); verified on the first Kotlin test run.
- **Migration regressions** — mitigated by keeping the Java tests green through
  the entire production-code conversion (Part C), converting tests only last.
