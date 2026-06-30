package com.droiddevgeeks.crashsink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashAttributorTest {

    private val attributor = CrashAttributor("com.example.")

    private fun frame(cls: String, method: String) = StackTraceElement(cls, method, "$cls.java", 1)

    private fun withTrace(t: Throwable, vararg frames: StackTraceElement): Throwable {
        t.stackTrace = arrayOf(*frames)
        return t
    }

    @Test fun ourFrameOnTopIsOurs() {
        val t = withTrace(
            NullPointerException(),
            frame("com.example.PaymentController", "pay"),
            frame("com.merchant.app.Checkout", "onClick")
        )
        assertTrue(attributor.isOurs(t))
    }

    @Test fun merchantCallbackOnTopIsNotOurs() {
        // The footgun: our frames are present but the top app frame is theirs.
        val t = withTrace(
            NullPointerException(),
            frame("com.merchant.app.Checkout", "onPaymentResult"),
            frame("com.example.PaymentController", "notifyResult")
        )
        assertFalse(attributor.isOurs(t))
    }

    @Test fun frameworkFramesAboveUsAreSkipped() {
        val t = withTrace(
            IllegalStateException(),
            frame("java.util.HashMap", "get"),
            frame("android.os.Handler", "dispatch"),
            frame("com.example.network.ResponseHandler", "handle")
        )
        assertTrue(attributor.isOurs(t))
    }

    @Test fun deepestCauseDeterminesOwnership() {
        val root = withTrace(IllegalArgumentException(), frame("com.example.network.Parser", "parse"))
        val wrapper = withTrace(RuntimeException(root), frame("com.merchant.app.Checkout", "submit"))
        assertTrue(attributor.isOurs(wrapper))
    }

    @Test fun shadedDependencyFrameIsOurs() {
        val t = withTrace(IllegalStateException(), frame("com.example.shaded.okhttp3.RealCall", "execute"))
        assertTrue(attributor.isOurs(t))
    }

    @Test fun allFrameworkTraceIsNotOurs() {
        val t = withTrace(
            NullPointerException(),
            frame("java.util.HashMap", "get"),
            frame("android.os.Handler", "dispatch")
        )
        assertFalse(attributor.isOurs(t))
    }

    @Test fun emptyTraceIsNotOurs() {
        assertFalse(attributor.isOurs(withTrace(RuntimeException())))
    }

    @Test fun prefixImpostorIsNotOurs() {
        // startsWith, not contains: this is NOT our package.
        val t = withTrace(RuntimeException(), frame("com.evil.com.example.Fake", "go"))
        assertFalse(attributor.isOurs(t))
    }

    @Test fun selfCausingThrowableDoesNotLoop() {
        val t = withTrace(RuntimeException(), frame("com.example.Foo", "bar"))
        // Java's initCause() prevents self-causation; bypass via reflection for this pathological case.
        val causeField = Throwable::class.java.getDeclaredField("cause")
        causeField.isAccessible = true
        causeField.set(t, t) // pathological; must terminate
        assertTrue(attributor.isOurs(t))
    }
}
