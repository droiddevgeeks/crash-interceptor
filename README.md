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

- Java 8+ bytecode (compiles with `--release 8`; runs on any modern JVM)
- **Android `minSdk` 21+ (Android 5.0) through API 36** — uses only `java.io` and core APIs
  available since API 21. **No core-library desugaring required.** (Deliberately avoids
  `java.nio.file`, which is API 26+ and not desugared, and `List.sort`/`Comparator.comparingLong`,
  which need API 24.)
- `org.json` on the runtime classpath (provided by the Android platform on every API level;
  add it explicitly only on a plain JVM — see below)

## Adding it to your build

This repo is a standalone Gradle project you can copy the `com.droiddevgeeks.crashsink`
package into, or publish as a library. Its only runtime dependency is `org.json`:

```kotlin
dependencies {
    // On Android, org.json ships with the platform — you can omit this.
    implementation("org.json:json:20231013")
}
```

---

## Quick start

```java
import com.droiddevgeeks.crashsink.CrashReporter;
import com.droiddevgeeks.crashsink.CrashSink;

import java.io.File;

// 1. Implement where captured crashes go (called on a healthy process, off the main thread).
CrashSink sink = (token, exceptionValues, level, culprit, timestamp) -> {
    // Forward to your telemetry pipeline / backend. exceptionValues is a JSON string.
    myBackend.uploadCrash(token, exceptionValues, level, culprit, timestamp);
};

// 2. Build the reporter. The last argument is YOUR package prefix — the thing that
//    marks a crash as "yours". Nothing about crashsink is hardcoded to a vendor.
File crashDir = new File(context.getFilesDir(), "crashes"); // any writable dir
CrashReporter reporter = CrashReporter.create(
        crashDir,
        /* fileCap         */ 20,      // keep at most N crash files on disk
        /* flushTimeoutMs  */ 1000L,   // max time the crashing thread waits for the write
        sink,
        /* ownedPrefix     */ "com.example.sdk.");

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

### The `CrashSink` you implement

```java
public interface CrashSink {
    void submit(String token,
                String exceptionValues, // JSON array string: type, redacted message, stack frames, thread id
                String level,           // "fatal"
                String culprit,         // e.g. "com.example.sdk.Worker in run"
                long timestamp);        // crash time, epoch millis
}
```

`submit` is invoked on a background thread during `install()` (next-launch ingestion), **not**
at crash time. If it throws, the crash file is kept and retried on a future `install()`.

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

| Parameter | Meaning | Suggested |
|---|---|---|
| `crashDir` | directory for crash files (one file per crash) | app-private storage |
| `fileCap` | max crash files retained (oldest evicted) | `20` |
| `flushTimeoutMillis` | max time the crashing thread blocks for the write | `1000` (tune from real write-latency telemetry) |
| `ownedPrefix` | package prefix that marks a crash as yours | your SDK's root package, e.g. `"com.example.sdk."` |

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

- **Logging:** the bundled `CrashLogger` writes to `System.err` (shows up in logcat). In a
  real Android module, swap it for `android.util.Log` — it's the one platform seam.

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
| `CrashLogger` | Minimal logger stand-in (swap for your platform logger) |

## Building & testing

```bash
./gradlew test    # 46 unit tests
```

Tests run on the host JVM (JUnit 4 + Mockito); no Android device or emulator required.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 droiddevgeeks.

Permissive and the de-facto standard for Android/JVM libraries: free to embed in
proprietary apps and SDKs, with an explicit patent grant that gives adopters' legal teams
the comfort MIT's silence on patents doesn't. See [`NOTICE`](NOTICE) for attribution.
