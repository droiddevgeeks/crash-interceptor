package com.droiddevgeeks.crashsink

import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CrashInterceptorTest {

    private fun anyCrash(): Throwable {
        val t = RuntimeException("boom")
        t.stackTrace = arrayOf(StackTraceElement("com.example.Foo", "bar", "Foo.java", 1))
        return t
    }

    @Test fun delegatesToPreviousAfterProcessing() {
        val previous = mock(Thread.UncaughtExceptionHandler::class.java)
        val attributor = mock(CrashAttributor::class.java)
        val processor = mock(CrashProcessor::class.java)
        `when`(attributor.isOurs(ArgumentMatchers.any())).thenReturn(true)

        val t = anyCrash()
        val thread = Thread.currentThread()
        CrashInterceptor(previous, attributor, processor).uncaughtException(thread, t)

        verify(processor, times(1)).persistBlocking(thread, t)
        verify(previous, times(1)).uncaughtException(thread, t)
    }

    @Test fun delegatesEvenWhenAttributionThrows() {
        val previous = mock(Thread.UncaughtExceptionHandler::class.java)
        val attributor = mock(CrashAttributor::class.java)
        val processor = mock(CrashProcessor::class.java)
        `when`(attributor.isOurs(ArgumentMatchers.any())).thenThrow(OutOfMemoryError("simulated"))

        val t = anyCrash()
        val thread = Thread.currentThread()
        CrashInterceptor(previous, attributor, processor).uncaughtException(thread, t)

        verify(previous, times(1)).uncaughtException(thread, t)
    }

    @Test fun notOursStillDelegatesAndSkipsProcessing() {
        val previous = mock(Thread.UncaughtExceptionHandler::class.java)
        val attributor = mock(CrashAttributor::class.java)
        val processor = mock(CrashProcessor::class.java)
        `when`(attributor.isOurs(ArgumentMatchers.any())).thenReturn(false)

        val t = anyCrash()
        val thread = Thread.currentThread()
        CrashInterceptor(previous, attributor, processor).uncaughtException(thread, t)

        verify(processor, times(0)).persistBlocking(ArgumentMatchers.any(), ArgumentMatchers.any())
        verify(previous, times(1)).uncaughtException(thread, t)
    }
}
