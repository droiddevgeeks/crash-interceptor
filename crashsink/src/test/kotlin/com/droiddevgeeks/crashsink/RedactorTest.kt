package com.droiddevgeeks.crashsink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private val redactor = Redactor()

    @Test fun nullMessageStaysNull() {
        assertNull(redactor.scrub(null))
    }

    @Test fun emptyMessageUnchanged() {
        assertEquals("", redactor.scrub(""))
    }

    @Test fun cleanMessageUnchanged() {
        assertEquals("Payment failed: timeout", redactor.scrub("Payment failed: timeout"))
    }

    @Test fun redactsPanWithSpaces() {
        assertEquals("card [REDACTED] declined", redactor.scrub("card 4111 1111 1111 1111 declined"))
    }

    @Test fun redactsPanContiguous() {
        assertEquals("[REDACTED]", redactor.scrub("4111111111111111"))
    }

    @Test fun redactsBearerToken() {
        assertTrue(redactor.scrub("auth Bearer abc.def.ghi123")!!.contains("[REDACTED]"))
    }

    @Test fun redactsUpiVpa() {
        assertEquals("payer [REDACTED] failed", redactor.scrub("payer john.doe@oksbi failed"))
    }
}
