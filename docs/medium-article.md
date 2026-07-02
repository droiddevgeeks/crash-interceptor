# Your Android SDK Just Crashed in Someone Else's App. Now What?

*How to report your own SDK's crashes without stepping on the host app's Crashlytics — and the gotchas nobody warns you about.*

---

Here's a situation every SDK developer eventually hits.

You ship an Android library. It runs inside apps you don't own, written by teams you'll never meet. One day a bug in your code throws an uncaught exception on a user's phone. The app dies. And you… have no idea it happened.

You want crash reports for *your* code. The obvious move is to install a crash handler:

```kotlin
Thread.setDefaultUncaughtExceptionHandler(myHandler)
```

Do that and you've just broken the host app.

Let me explain why, because the reason is the whole story.

## Android gives you exactly one slot

`Thread.setDefaultUncaughtExceptionHandler` is a single global setter. One slot. Whoever calls it last wins, and everyone before them is silently evicted.

The host app almost certainly got there first. They're running Firebase Crashlytics, or Sentry, or Bugsnag — and every one of those works by owning that slot. When you call `setDefaultUncaughtExceptionHandler` from your SDK, you don't *add* a handler. You *replace* theirs. Their crash reporting goes dark, and they won't find out until an incident review turns up a suspiciously quiet dashboard.

So the naive approach is off the table. You can't own the slot. But you still need your crashes.

## The decorator: don't take the slot, wrap it

The trick is to stop thinking of the handler as something you *own* and start thinking of it as something you *decorate*.

When your SDK initializes, read whatever handler is already installed. Then install your own — one that inspects each crash, keeps the ones that belong to you, and **always** forwards the exception to the handler you found. The host's Crashlytics still fires. You just got a look on the way through.

```
crash → [your interceptor] → host's handler (Crashlytics/Sentry/…) → system default
              │
              └─ if the crash is yours: capture it, then delegate anyway
```

That single rule — *always delegate downstream* — is what makes this safe to embed. You are a guest. You look, you don't touch. The host's reporter sees 100% of crashes exactly like it did before you showed up.

This is the idea behind **crashsink**, a tiny library I built for exactly this problem. The rest of this post is the stuff I learned making it actually work in the wild, because the decorator idea is the easy 20%.

## "Is this crash mine?" is harder than it looks

You only want to capture crashes caused by *your* code. A crash bubbling up from the host, or from some other SDK, isn't yours to report — you'd just be spamming your own backend with noise you can't act on.

The naive check — "does the top stack frame live in my package?" — is wrong in a subtle way. Exceptions get wrapped. A `RuntimeException` caused by an `IOException` caused by the actual bug: the top frame is often framework code, not the culprit. And the top of the stack is frequently `java.*` or `android.*` plumbing regardless.

So attribution walks the chain properly:

1. Follow `getCause()` down to the deepest cause (bounded, so a malicious cyclic cause chain can't hang you).
2. Skip framework frames — `java.`, `kotlin.`, `android.`, and friends.
3. Look at the **first application frame that's left**. Does its class name start with your configured package prefix?

If yes, it's yours. If no, hands off.

There's a nice edge case this gets right. Say the host registers a callback with your SDK, you invoke it, and *their* callback throws. The crash passes *through* your code, but the culprit frame belongs to the host. The starts-with check leaves it to them — which is correct. A crash routing through you isn't a crash caused by you.

```kotlin
CrashReporter.create(
    context,
    sink = { token, exceptionValues, level, culprit, timestamp, contexts ->
        // ship it to your backend
    },
    ownedPrefix = "com.mycompany.mysdk."
)
```

One string, `ownedPrefix`, decides what you own.

## The dying-thread problem

Now the part that separates a toy from something you'd actually embed in a payment SDK, an analytics SDK, a chat SDK, a maps SDK, an ads SDK, or any other kind of mobile SDK.

When `uncaughtException` fires, you are running on a thread that is *about to die*. The process is in an undefined state. Whatever you do here, you do under the worst conditions your code will ever see:

- **You can't throw.** An exception in a crash handler is the definition of adding insult to injury — and it can swallow the original crash entirely.
- **You can't block forever.** Hang here and you turn a clean crash into an ANR, which is a *worse* user experience than the crash you were trying to report.
- **You shouldn't allocate.** The heap may be exhausted (plenty of crashes *are* OOMs). Allocating in the handler can fail or make things worse.

So crashsink does almost nothing on the dying thread. It hands the actual work — building the crash payload, redacting it, writing it to disk — to a dedicated daemon thread, and the dying thread just waits on a latch with a hard timeout. Then, no matter what happened, it delegates downstream in a `finally` block. The whole body is wrapped in `catch (Throwable)`. Nothing the interceptor does can prevent the host's handler from running.

The other half of that design: **you don't send the crash at crash time.** Network calls from a dying process are a fantasy. Instead it's two phases:

- **At crash time:** write a small, redacted file to disk. Fast, bounded, local.
- **On the next app launch:** read those files, hand each to your `sink` to upload, delete on success, keep-and-retry on failure.

Write now, deliver later. It's the only pattern that survives a process that's actively falling over.

## The gotchas I only found by shipping

The core pipeline is maybe half the work. The other half is the stuff that bites you in production.

**Double initialization stacks interceptors.** A host might call your SDK's `init()` from more than one place — Application, then an Activity, then a deep link handler. Naively, each call wraps the chain again, and now a single crash gets captured and delivered *twice*. The fix: before installing, walk the existing chain looking for an interceptor that already owns your prefix. Found one? Adopt it, don't stack. Install-once has to be an invariant, not a hope.

**Two SDKs, one process, one directory.** If two different SDKs both embed crashsink in the same app, and both write crash files to the same folder, they'll trample each other. The fix is to namespace the crash directory per package prefix — `filesDir/crashsink/<your-prefix>/` — so two guests (or a guest and the host) never collide.

**Obfuscation silently breaks attribution.** This one's nasty because it fails *quietly*. Attribution matches on package names. If a consumer runs R8/ProGuard and obfuscates your package, `com.mycompany.mysdk.Worker` becomes `a.b.c` at runtime, your prefix stops matching, and crashes are silently not captured. No error. Just… nothing. The mitigation ships *inside the AAR*: a `consumer-rules.pro` with `-keeppackagenames` and `-keepattributes SourceFile,LineNumberTable`, so the rules travel with the artifact and apply to the consumer's R8 run automatically.

**Keep the public API tiny.** Everything that isn't the consumer's contract is `internal`. The entire public surface is one factory method and one interface (`CrashSink`, a SAM you implement as a lambda). Small surface means you can refactor the guts without breaking anyone, and there's less for a consumer to misuse.

## Installing it

crashsink is on JitPack, which builds straight from a Git tag — no publish step, no auth needed for public repos.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.droiddevgeeks:crash-interceptor:0.1.0")
}
```

Then wire it up from your SDK's own init entry point — *not* from an Application class, because as a guest SDK you don't own one:

```kotlin
object MySdk {
    private var reporter: CrashReporter? = null

    @Synchronized
    fun init(context: Context) {
        if (reporter != null) return  // idempotent — double-init is safe
        reporter = CrashReporter.create(
            context.applicationContext,
            { _, exceptionValues, _, culprit, timestamp, contexts ->
                // POST to your backend here
            },
            ownedPrefix = "com.mycompany.mysdk."
        ).apply {
            install()                 // also flushes crashes from the previous run
            startCapturing("session-id")
        }
    }
}
```

That's it. Your crashes go to you; everything else — including your crashes, re-thrown — goes to the host's reporter.

## Wrapping up

Crash reporting for a guest SDK is a constraint problem more than a code problem. Android hands out one crash-handler slot, the host already owns it, and your job is to observe without disturbing. Decorate the slot, always delegate, attribute carefully, and do the dangerous work off the dying thread.

crashsink is open source — [github.com/droiddevgeeks/crash-interceptor](https://github.com/droiddevgeeks/crash-interceptor). It's small enough to read in one sitting, which is kind of the point. If you're shipping an SDK and flying blind on your own crashes, give it a look.

---

*Building an Android SDK and dealing with the same constraints? I'd genuinely like to hear how you're handling it — drop a comment.*
