package com.droiddevgeeks.crashsink;

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
