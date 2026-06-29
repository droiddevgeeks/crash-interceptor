package com.cashfree.pg.cf_analytics.crash;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrashAttributorTest {

    private final CrashAttributor attributor = new CrashAttributor();

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, cls + ".java", 1);
    }

    private static Throwable withTrace(Throwable t, StackTraceElement... frames) {
        t.setStackTrace(frames);
        return t;
    }

    @Test
    public void ourFrameOnTopIsOurs() {
        Throwable t = withTrace(new NullPointerException(),
                frame("com.cashfree.pg.PaymentController", "pay"),
                frame("com.merchant.app.Checkout", "onClick"));
        assertTrue(attributor.isOurs(t));
    }

    @Test
    public void merchantCallbackOnTopIsNotOurs() {
        // The footgun: our frames are present but the top app frame is theirs.
        Throwable t = withTrace(new NullPointerException(),
                frame("com.merchant.app.Checkout", "onPaymentResult"),
                frame("com.cashfree.pg.PaymentController", "notifyResult"));
        assertFalse(attributor.isOurs(t));
    }

    @Test
    public void frameworkFramesAboveUsAreSkipped() {
        Throwable t = withTrace(new IllegalStateException(),
                frame("java.util.HashMap", "get"),
                frame("android.os.Handler", "dispatch"),
                frame("com.cashfree.pg.network.ResponseHandler", "handle"));
        assertTrue(attributor.isOurs(t));
    }

    @Test
    public void deepestCauseDeterminesOwnership() {
        Throwable root = withTrace(new IllegalArgumentException(),
                frame("com.cashfree.pg.network.Parser", "parse"));
        Throwable wrapper = withTrace(new RuntimeException(root),
                frame("com.merchant.app.Checkout", "submit"));
        assertTrue(attributor.isOurs(wrapper));
    }

    @Test
    public void shadedDependencyFrameIsOurs() {
        Throwable t = withTrace(new IllegalStateException(),
                frame("com.cashfree.pg.shaded.okhttp3.RealCall", "execute"));
        assertTrue(attributor.isOurs(t));
    }

    @Test
    public void allFrameworkTraceIsNotOurs() {
        Throwable t = withTrace(new NullPointerException(),
                frame("java.util.HashMap", "get"),
                frame("android.os.Handler", "dispatch"));
        assertFalse(attributor.isOurs(t));
    }

    @Test
    public void emptyTraceIsNotOurs() {
        assertFalse(attributor.isOurs(withTrace(new RuntimeException())));
    }

    @Test
    public void prefixImpostorIsNotOurs() {
        // startsWith, not contains: this is NOT our package.
        Throwable t = withTrace(new RuntimeException(),
                frame("com.evil.com.cashfree.pg.Fake", "go"));
        assertFalse(attributor.isOurs(t));
    }

    @Test
    public void selfCausingThrowableDoesNotLoop() throws Exception {
        Throwable t = withTrace(new RuntimeException(),
                frame("com.cashfree.pg.Foo", "bar"));
        // Java's initCause() prevents self-causation; bypass via reflection for this pathological case
        java.lang.reflect.Field causeField = Throwable.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(t, t); // pathological; must terminate
        assertTrue(attributor.isOurs(t));
    }
}
