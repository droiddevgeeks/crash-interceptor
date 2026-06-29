# Task 1 — Redactor (PII scrubbing)

This is the first task of a standalone Java crash-interceptor library. The Redactor is a
pure, dependency-free class that scrubs sensitive substrings out of exception messages
before they are persisted.

## Global constraints (apply verbatim)
- Java 8 source level. No `var`, no records, no switch-expressions.
- Package: `com.cashfree.pg.cf_analytics.crash`.
- Source path: `src/main/java/com/cashfree/pg/cf_analytics/crash/Redactor.java`
- Test path: `src/test/java/com/cashfree/pg/cf_analytics/crash/RedactorTest.java`
- Test command: `./gradlew test --tests "com.cashfree.pg.cf_analytics.crash.RedactorTest"`
- Follow TDD: write the failing test first, run it red, implement, run it green, commit.

## Interface to produce
- `String Redactor.scrub(String message)` — null-safe; returns `null` for `null` input,
  `""` for `""`; replaces PAN / bearer-token / UPI-VPA substrings with `"[REDACTED]"`.

## Step 1 — write this exact failing test

```java
package com.cashfree.pg.cf_analytics.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RedactorTest {

    private final Redactor redactor = new Redactor();

    @Test
    public void nullMessageStaysNull() {
        assertNull(redactor.scrub(null));
    }

    @Test
    public void emptyMessageUnchanged() {
        assertEquals("", redactor.scrub(""));
    }

    @Test
    public void cleanMessageUnchanged() {
        assertEquals("Payment failed: timeout", redactor.scrub("Payment failed: timeout"));
    }

    @Test
    public void redactsPanWithSpaces() {
        assertEquals("card [REDACTED] declined", redactor.scrub("card 4111 1111 1111 1111 declined"));
    }

    @Test
    public void redactsPanContiguous() {
        assertEquals("[REDACTED]", redactor.scrub("4111111111111111"));
    }

    @Test
    public void redactsBearerToken() {
        assertTrue(redactor.scrub("auth Bearer abc.def.ghi123").contains("[REDACTED]"));
    }

    @Test
    public void redactsUpiVpa() {
        assertEquals("payer [REDACTED] failed", redactor.scrub("payer john.doe@oksbi failed"));
    }
}
```

## Step 3 — implement with this exact code

```java
package com.cashfree.pg.cf_analytics.crash;

import java.util.regex.Pattern;

/** Strips known-sensitive substrings from exception messages before persistence. */
public final class Redactor {

    private static final String MASK = "[REDACTED]";

    // 13-19 digit card numbers, optionally separated by single spaces or dashes.
    private static final Pattern PAN =
            Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    // Bearer / auth tokens.
    private static final Pattern BEARER =
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    // UPI VPA: handle@psp
    private static final Pattern VPA =
            Pattern.compile("\\b[A-Za-z0-9.\\-_]{2,256}@[A-Za-z]{2,64}\\b");

    public String scrub(final String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String out = PAN.matcher(message).replaceAll(MASK);
        out = BEARER.matcher(out).replaceAll(MASK);
        out = VPA.matcher(out).replaceAll(MASK);
        return out;
    }
}
```

## Verify & commit
- Run the test command above; expect 7 tests passing.
- Commit only these two files with message:
  `feat(crash): add PII Redactor for exception messages`

## Report contract
Write your full report to `/Users/kishankumarmaurya/Development/AI/crash-interceptor/.sdd/task-1-report.md`
including: files created, the exact test command output (pass/fail counts), the commit hash,
and any concerns. Return only: STATUS (DONE/BLOCKED/etc.), commit hash, one-line test summary.
