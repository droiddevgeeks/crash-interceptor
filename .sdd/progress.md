# Crash Interceptor — Subagent-Driven Progress Ledger

Project: /Users/kishankumarmaurya/Development/AI/crash-interceptor (standalone plain-Java)
Plan: docs/superpowers/plans/2026-06-29-cashfree-sdk-crash-interceptor.md (Android), executed standalone.

Standalone adaptations (apply to every task):
- Source paths: `src/main/java/...` and `src/test/java/...` (no `cf-analytics/` prefix).
- Test command: `./gradlew test --tests "<FQN>"`.
- `CFLoggerService` is a stubbed stand-in already present (same package/API).
- `org.json` is an `implementation` dep (already in build.gradle.kts); Task 4 does NOT edit build deps.
- No `androidx.annotation` — drop @NonNull/@Nullable.
- Task 8 rewritten as a standalone `CrashReporter` facade + demo `CrashSink` (no CFAnalyticsService).

## Status
- scaffold: complete (commit: initial)
- Task 1 (Redactor): pending
- Task 2 (CrashAttributor): pending
- Task 3 (CrashFileStore): pending
- Task 4 (CrashProcessor): pending
- Task 5 (CrashInterceptor): pending
- Task 6 (CrashHandlerManager): pending
- Task 7 (CrashIngestor + CrashSink): pending
- Task 8 (CrashReporter facade wiring): pending

## Minor findings roll-up (for final review)
(none yet)
