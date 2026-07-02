# crashsink

A tiny, dependency-light crash interceptor for the JVM that captures **only the crashes
caused by your code** and stays out of everyone else's way.

It's built for the awkward situation a library/SDK lives in: the host app probably already
runs Firebase Crashlytics, Sentry, or Bugsnag, and Android exposes a **single**
`Thread.setDefaultUncaughtExceptionHandler` slot. crashsink inserts itself as a *decorator*
in that chain — it inspects each crash, persists the ones that originate in your package,
and **always delegates** downstream so the host's reporter still sees everything.

```
crash → [crashsink] → host's handler (Firebase/Sentry/…) → system
            │
            └─ if the crash is yours: redact → write a small file → (next launch) hand to your sink
```

---

## Why not just use Crashlytics/Sentry?

Those are **app-owner** tools — they assume they own the process and capture *everything*.
A guest SDK that grabs the handler to log only its own crashes would either fight the host's
reporter or vacuum up the host's crashes (and their data). crashsink is the opposite: it
attributes precisely, captures narrowly, and never breaks the chain.

## Guarantees

- **Never crashes the host.** The whole crash-path is wrapped in `catch (Throwable)` and
  always delegates to the previous handler in a `finally`. A bug in crashsink can't become a
  second crash or swallow the original.
- **Never causes an ANR.** Crash-time work is bounded by a configurable latch timeout
  (default 1s). No network, no database, no lock, no `fsync` on the crash path.
- **Captures only your crashes.** Attribution keys on the **topmost application frame** of
  the deepest cause — so a crash that merely passes *through* your code (e.g. a host callback
  you invoked that threw) is correctly left to the host.
- **Redacts PII before persisting.** PAN / bearer-token / UPI-VPA patterns are scrubbed from
  exception messages.
- **No crash lost on a flaky backend.** A failed delivery keeps the crash file for retry on a
  later launch; only unparseable files are dropped.

## Requirements

This is an **Android library** (`com.android.library`):

- **`minSdk` 21 (Android 5.0) → `compileSdk` 36** — uses only `java.io` and core APIs available
  since API 21, so **no core-library desugaring required**. (Deliberately avoids `java.nio.file`,
  which is API 26+ and not desugared, and `List.sort`/`Comparator.comparingLong`, which need API 24.)
  The code is in fact API-19-safe, so it also drops into `minSdk 19` modules unchanged.
- **Written in Kotlin**, compiled to **Java 8 bytecode** (`jvmTarget = 1.8`, broad device reach).
  The public API is fully Java-interoperable — static factories, SAM `CrashSink`, and plain
  field access on `DeviceMetadata` all work from Java (see Quick start above and the `:sample`
  app's `JavaInteropDemo.java`).
- Toolchain: **AGP 8.11.1 / Gradle 8.14.3 / Kotlin 2.0.21** (pinned to match a typical Android module).
- `org.json` is provided by the Android platform — no dependency to add. (It's pulled in only as
  a *test* dependency, because the platform's `org.json` is a throwing stub under host unit tests.)

## Adding it to your build

Depend on the published AAR (or include the module):

```kotlin
dependencies {
    implementation("com.droiddevgeeks:crashsink:<version>")
}
```

Or copy the `com.droiddevgeeks.crashsink` package into an existing Android library module —
it has a single platform dependency (`android.util.Log`, in `CrashLogger`).

---

## Quick start

```java
import com.droiddevgeeks.crashsink.CrashReporter;
import com.droiddevgeeks.crashsink.CrashSink;

// 1. Implement where captured crashes go (called on a healthy process, off the main thread).
CrashSink sink = (token, exceptionValues, level, culprit, timestamp, contexts) -> {
    // Forward to your telemetry pipeline / backend. exceptionValues and contexts are JSON strings.
    myBackend.uploadCrash(token, exceptionValues, level, culprit, timestamp, contexts);
};

// 2. Build the reporter from a Context — it derives a per-SDK crash dir from getFilesDir() and
//    attaches device/app metadata to every crash. `ownedPrefix` is YOUR package prefix: the
//    thing that marks a crash as "yours". Nothing about crashsink is hardcoded to a vendor.
CrashReporter reporter = CrashReporter.create(context, sink, "com.example.sdk.");
// fileCap + flushTimeoutMillis are optional; pass them to tune (defaults: 20 files, 1000ms):
//   CrashReporter.create(context, sink, "com.example.sdk.", /*fileCap*/ 50, /*flushTimeoutMs*/ 1500L);

// 3. Install into the uncaught-handler chain. This also ingests any crashes
//    that were persisted on a previous run and hands them to your sink.
reporter.install();

// 4. Begin/stop capturing around the work you care about (e.g. a session).
//    The token is attached to every crash captured while tracking is on.
reporter.startCapturing(sessionId);
// ... run your SDK's work ...
reporter.stopCapturing();

// 5. When the reporter is no longer needed (or before re-creating one), release its threads.
reporter.shutdown();
```

From **Kotlin** the same API reads naturally — `fileCap`/`flushTimeoutMillis` default, so omit them:

```kotlin
val reporter = CrashReporter.create(
    context,
    sink = CrashSink { token, exceptionValues, level, culprit, timestamp, contexts ->
        myBackend.uploadCrash(token, exceptionValues, level, culprit, timestamp, contexts)
    },
    ownedPrefix = "com.example.sdk.")          // or add fileCap = 50, flushTimeoutMillis = 1500L
reporter.install()
reporter.startCapturing(sessionId)
```

### Where to install (you're a guest SDK)

crashsink is a **guest-SDK** reporter, so install it from **your SDK's own init entry point** —
the method the host app already calls to set you up — not from the host's `Application`. A
third-party SDK can't assume the host even has an `Application` subclass; what you always have is
the `Context` the host passes you.

```kotlin
object MySdk {
    @Volatile private var reporter: CrashReporter? = null

    @Synchronized
    fun init(context: Context) {
        if (reporter != null) return                      // guard against a host that double-inits
        // The Context-based create stores crashes in a per-SDK private directory (see below).
        reporter = CrashReporter.create(context.applicationContext, sink, "com.example.sdk.")
            .apply { install(); startCapturing(sessionId) }
    }
}
```

Install as early as your init runs — crashes before that point can't be captured (they still
reach the host's reporter). Use `applicationContext` so you never retain an Activity.

**Per-SDK crash directory.** `create` stores crashes in `filesDir/crashsink/<your-prefix>` — derived
from `ownedPrefix`, so two crashsink-using SDKs (or a guest SDK and a crashsink-using host) in one
app get distinct directories and never cross-deliver or cross-delete each other's crash files. You
don't manage the directory — crashsink does. Just keep your prefix disjoint from other SDKs' —
`com.a.` and `com.a.plugin.` overlap under the starts-with attribution rule.

**Double-init is safe.** If `install()` runs more than once for the same `ownedPrefix` (host calls
your init from several places), crashsink **adopts the interceptor already in the chain** instead
of stacking a second one — so a single crash is never delivered twice. A different `ownedPrefix` is
treated as a genuinely different SDK and chains normally. Still guard `create` itself
(as above): each `CrashReporter` you build owns background threads that only `shutdown()` releases,
and the adopt-on-install path can't reclaim a spare reporter's threads.

### The `CrashSink` you implement

```java
public interface CrashSink {
    void submit(String token,
                String exceptionValues, // JSON array string: type, redacted message, stack frames, thread id
                String level,           // "fatal"
                String culprit,         // e.g. "com.example.sdk.Worker in run"
                long timestamp,         // crash time, epoch millis
                String contexts);       // JSON object: { device: {...}, memory: {...} }  ("{}" if none)
}
```

`submit` is invoked on a background thread during `install()` (next-launch ingestion), **not**
at crash time. If it throws, the crash file is kept and retried on a future `install()`.

`contexts` carries device/app metadata (collected once at `create(Context, …)` time, off the
crash path) and JVM heap memory (read cheaply at crash time):

```json
{
  "device": { "os_version": "13", "sdk_int": 33, "manufacturer": "Google",
              "model": "Pixel 7", "app_version_name": "1.4.0", "app_version_code": 140 },
  "memory": { "heap_free": 12345678, "heap_total": 50331648, "heap_max": 268435456 }
}
```

(`create` always builds from a `Context`, so the `device` block and `memory` are both present.)

---

## How attribution works

On each uncaught exception crashsink:

1. Walks to the **deepest cause** (`getCause()` chain, bounded to guard cycles).
2. Skips framework frames (`java.*`, `kotlin.*`, `android.*`, `androidx.*`, `dalvik.*`,
   `com.google.android.*`, `sun.*`, …).
3. Takes the first remaining (application) frame. If its class name **starts with your
   `ownedPrefix`**, the crash is yours.

This is deliberately conservative. Example — a host callback you invoked throws:

```
java.lang.NullPointerException
    at com.host.app.Checkout.onResult(...)        ← top app frame is the HOST's → NOT captured
    at com.example.sdk.PaymentController.notify(...)   ← your frames are present, but you didn't cause it
```

crashsink leaves that one to the host's reporter.

> **Shaded dependencies:** if you relocate your third-party deps under your own prefix
> (e.g. `com.example.sdk.shaded.okhttp3.*`), crashes in them are attributed to you, and
> never confused with the host's copy of the same library.

---

## Lifecycle & threading

| Phase | Thread | What happens |
|---|---|---|
| `install()` | calling thread | capture current default handler as `previous`, install the decorator, kick off next-launch ingestion |
| crash | the crashing thread | attribute → (if yours) redact + submit a write to a worker, **wait ≤ `flushTimeoutMs`** → **always** delegate to `previous` |
| crash (write) | `cf-crash-writer` daemon | atomic temp-file write + rename, no `fsync` |
| ingestion | `cf-crash-ingest` daemon | read completed crash files → `sink.submit` → delete on success |

The timeout is a **ceiling, not a cost**: the write normally completes in single-digit
milliseconds and the crashing thread is released immediately. The timeout only bites if the
disk stalls — and then crashsink delegates anyway rather than hang.

## Configuration

| Parameter | Meaning | Default / Suggested |
|---|---|---|
| `context` | any `Context`; the crash dir is derived from `getFilesDir()` (you don't pick it) | — (required) |
| `sink` | where captured crashes are delivered (on next launch, off the main thread) | — (required) |
| `fileCap` | max crash files retained; **oldest evicted** beyond this | `20` (`CrashReporter.DEFAULT_FILE_CAP`) |
| `flushTimeoutMillis` | max time the crashing thread blocks for the write | `1000` (`CrashReporter.DEFAULT_FLUSH_TIMEOUT_MS`; tune from real write-latency telemetry) |
| `ownedPrefix` | package prefix that marks a crash as yours | your SDK's root package, e.g. `"com.example.sdk."` |

`fileCap` and `flushTimeoutMillis` are optional trailing parameters — omit them for the defaults, or
pass them to tune. In Kotlin they're default arguments; `@JvmOverloads` gives Java the same
omit-or-pass choice:

```kotlin
CrashReporter.create(context, sink, "com.example.sdk.")            // defaults: cap 20, timeout 1000ms
CrashReporter.create(context, sink, "com.example.sdk.", 50, 1500L) // explicit
```

**`fileCap` is a bound on the failure path, not the steady state.** In the happy path (crash →
next-launch upload → delete) you rarely hold more than one file. It matters when delivery fails
(a down/flaky `CrashSink` keeps files for retry), when the app crash-loops (files written faster
than they ingest), or across multiple processes sharing the dir — there the cap stops the crash
dir from growing without limit, evicting the oldest crashes first. Raise it to tolerate longer
outages; `0` disables eviction (unbounded — avoid on-device); `1` defeats the retry guarantee.

## Android integration notes

- **API level:** clean on `minSdk` **21 → 36** with **no core-library desugaring** required
  (see Requirements). No `java.nio.file`, no API-24 collection defaults.

- **R8 / ProGuard — read this, it's the easy way to silently capture nothing.** Attribution
  matches each crash's stack-frame class name against your `ownedPrefix`. If your build
  **obfuscates** your SDK, runtime frames become `a.b.c` instead of `com.example.sdk.*`, so
  nothing matches and your crashes are silently *not* captured (no error — just silence).
  Keep your package names so the prefix still matches (class names may still be shortened —
  the match is on the package prefix, not the full class name), and keep line numbers for
  readable traces:

  ```proguard
  # crashsink: preserve your SDK's package names so attribution keeps working after R8
  -keeppackagenames com.example.sdk.**
  # readable line numbers in captured stack traces
  -keepattributes SourceFile,LineNumberTable
  ```

  (If you ship a pre-obfuscated AAR, pass your *published* obfuscated root package as
  `ownedPrefix` instead.)

  **Who adds the rule, and where — this depends on how you use crashsink:**

  crashsink itself **cannot** ship this rule for you. R8 runs at the *host app's* build time
  and only you know your package; there is no runtime hook that can undo obfuscation after the
  fact. So the rule must live with whoever owns the attributed code. Two shapes:

  - **Case A — an app attributes its own crashes.** The app passes its own package as
    `ownedPrefix` and adds `-keeppackagenames com.theirapp.**` to its **own** `proguard-rules.pro`.
    Done — it owns the code and its own build.

  - **Case B — your SDK embeds crashsink** (the "guest-SDK crash reporter" use case). You attribute
    *your* SDK's crashes inside a host app you don't control. Ship the keep rule in **your SDK's own
    `consumer-rules.pro`**, bundled into your AAR — R8 applies every dependency's consumer rules to
    the host build automatically, so **the host app adds nothing and knows nothing**. This is the
    intended path. Copy [`docs/embedding-sdk-consumer-rules.pro`](docs/embedding-sdk-consumer-rules.pro)
    into your SDK module, replace `com.yoursdk` with your package, and wire it with
    `consumerProguardFiles("consumer-rules.pro")`.

    ```
    Host App  ──depends on──▶  YourSDK.aar (embeds crashsink, ownedPrefix "com.yoursdk.")
                                └─ consumer-rules.pro: -keeppackagenames com.yoursdk.**
                                   ▲ travels inside your AAR; enforced in the host's R8 run
    ```

    > ⚠️ If you forget this in Case B, there is no error — attribution just silently captures
    > nothing in obfuscated host builds. Verify against a real minified build, not a debug one.

  crashsink's **own** [`consumer-rules.pro`](consumer-rules.pro) is wired via
  `consumerProguardFiles(...)`, so it is **bundled into the crashsink AAR** (as `proguard.txt`) and
  applied automatically — it keeps crashsink's public API and carries the fill-in template above.
  [`proguard-rules.pro`](proguard-rules.pro) is wired for self-minifying the AAR (off by default, as
  libraries usually ship un-minified).

- **Logging:** `CrashLogger` wraps `android.util.Log` — the one platform seam. (Host unit tests
  use `testOptions.unitTests.isReturnDefaultValues = true`, so `Log` calls are no-ops there.)

- **Threading / StrictMode:** all disk I/O runs on background executors, never the main
  thread, so StrictMode's main-thread disk policies won't fire. The only main-thread time is
  the bounded `flushTimeoutMillis` wait during an actual crash (the process is dying anyway).

- **Multi-process apps:** crash files include a per-process salt, so separate processes
  (`:remote` services, isolated WebView, etc.) writing to a shared crash dir won't collide.
  Prefer giving each process its own crash dir if you can.

## Out of scope (v1)

- **Native / NDK crashes** (SIGSEGV) — need a signal handler; different mechanism.
- **ANRs** — not exceptions; need separate detection.
- Upload/sampling policy — that's your `CrashSink`'s job.

---

## Architecture

| Class | Responsibility |
|---|---|
| `CrashReporter` | Public facade — wires everything; `install` / `startCapturing` / `stopCapturing` / `shutdown` |
| `CrashInterceptor` | The decorator in the handler chain; catches `Throwable`, always delegates |
| `CrashAttributor` | "Is the top application frame ours?" (uses `ownedPrefix`) |
| `CrashProcessor` | Build payload → redact → bounded write → dedupe; never throws |
| `CrashFileStore` | Lock-free, one-file-per-crash, atomic temp+rename, capped |
| `CrashHandlerManager` | Explicit install + idempotent re-assert (self-heals if displaced) |
| `CrashIngestor` + `CrashSink` | Next-launch delivery; retry-safe |
| `Redactor` | PAN / token / VPA scrubbing |
| `CrashFrames` | Shared stack-frame classification |
| `DeviceMetadata` | Immutable holder for device/app metadata (`@JvmField` fields for Java callers) |
| `AndroidDeviceMetadata` | Collects `DeviceMetadata` from a `Context` (`Build.*` + `PackageManager`) |
| `CrashLogger` | Thin wrapper over `android.util.Log` (the one platform seam) |

## Building & testing

The repo is a two-module Gradle build: `:crashsink` (the library) and `:sample` (a runnable
demo app that consumes it).

```bash
./gradlew :crashsink:testDebugUnitTest   # 50 unit tests
./gradlew :crashsink:assembleRelease     # -> crashsink/build/outputs/aar/crashsink-release.aar
./gradlew :sample:assembleDebug          # builds the demo APK
```

Unit tests run on the host JVM (JUnit 4 + Mockito, `returnDefaultValues` for `android.*`); no
device or emulator required. Requires the Android SDK (`ANDROID_HOME`) with `compileSdk 36`.

The `:sample` app uses Firebase Crashlytics, so it needs a `google-services.json`. That file is
**gitignored** (it carries an app-restricted Firebase client key we keep out of the repo). Before
building the sample, copy the template and drop in your own Firebase Android app config:

```bash
cp sample/google-services.json.example sample/google-services.json   # then fill in real values
```

The `:sample` app demonstrates the contract end to end: a "Crash in SDK code" button (captured),
a "Crash in host code" button (delegated to the host handler), and a "Java interop demo" button
that drives the API from a Java class — see
[`JavaInteropDemo.java`](sample/src/main/java/com/droiddevgeeks/sample/JavaInteropDemo.java).

## License

[Apache License 2.0](LICENSE) — Copyright 2026 droiddevgeeks.

Permissive and the de-facto standard for Android/JVM libraries: free to embed in
proprietary apps and SDKs, with an explicit patent grant that gives adopters' legal teams
the comfort MIT's silence on patents doesn't. See [`NOTICE`](NOTICE) for attribution.
