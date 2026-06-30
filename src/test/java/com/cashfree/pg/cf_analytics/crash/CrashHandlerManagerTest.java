package com.cashfree.pg.cf_analytics.crash;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CrashHandlerManagerTest {

    private Thread.UncaughtExceptionHandler original;
    private CrashHandlerManager manager;

    @Before public void setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler();
        manager = new CrashHandlerManager(new CrashAttributor(), mock(CrashProcessor.class));
    }

    @After public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    @Test public void installPutsOurInterceptorInTheSlot() {
        manager.install();
        assertSame(manager.getInstalled(), Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void installCapturesPreviousAsDelegate() {
        Thread.UncaughtExceptionHandler host = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(host);
        manager.install();
        assertSame(host, manager.getInstalled().getPrevious());
    }

    @Test public void reassertIsNoOpWhenStillInSlot() {
        manager.install();
        CrashInterceptor first = manager.getInstalled();
        manager.reassert();
        assertSame(first, manager.getInstalled());
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void reassertRewrapsWhenDisplaced() {
        manager.install();
        CrashInterceptor first = manager.getInstalled();
        // A library installs itself after us without delegating to us.
        Thread.UncaughtExceptionHandler intruder = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(intruder);

        manager.reassert();

        assertNotSame(first, manager.getInstalled());
        assertSame(manager.getInstalled(), Thread.getDefaultUncaughtExceptionHandler());
        assertSame(intruder, manager.getInstalled().getPrevious());
    }
}
