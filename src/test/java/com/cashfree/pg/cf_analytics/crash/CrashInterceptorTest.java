package com.cashfree.pg.cf_analytics.crash;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

public class CrashInterceptorTest {

    private static Throwable anyCrash() {
        Throwable t = new RuntimeException("boom");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.cashfree.pg.Foo", "bar", "Foo.java", 1)});
        return t;
    }

    @Test public void delegatesToPreviousAfterProcessing() {
        Thread.UncaughtExceptionHandler previous = mock(Thread.UncaughtExceptionHandler.class);
        CrashAttributor attributor = mock(CrashAttributor.class);
        CrashProcessor processor = mock(CrashProcessor.class);
        when(attributor.isOurs(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        Throwable t = anyCrash();
        Thread thread = Thread.currentThread();
        new CrashInterceptor(previous, attributor, processor).uncaughtException(thread, t);

        verify(processor, times(1)).persistBlocking(thread, t);
        verify(previous, times(1)).uncaughtException(thread, t);
    }

    @Test public void delegatesEvenWhenAttributionThrows() {
        Thread.UncaughtExceptionHandler previous = mock(Thread.UncaughtExceptionHandler.class);
        CrashAttributor attributor = mock(CrashAttributor.class);
        CrashProcessor processor = mock(CrashProcessor.class);
        when(attributor.isOurs(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new OutOfMemoryError("simulated"));

        Throwable t = anyCrash();
        Thread thread = Thread.currentThread();
        new CrashInterceptor(previous, attributor, processor).uncaughtException(thread, t);

        verify(previous, times(1)).uncaughtException(thread, t);
    }

    @Test public void notOursStillDelegatesAndSkipsProcessing() {
        Thread.UncaughtExceptionHandler previous = mock(Thread.UncaughtExceptionHandler.class);
        CrashAttributor attributor = mock(CrashAttributor.class);
        CrashProcessor processor = mock(CrashProcessor.class);
        when(attributor.isOurs(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        Throwable t = anyCrash();
        Thread thread = Thread.currentThread();
        new CrashInterceptor(previous, attributor, processor).uncaughtException(thread, t);

        verify(processor, times(0))
                .persistBlocking(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(previous, times(1)).uncaughtException(thread, t);
    }
}
