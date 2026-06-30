package com.droiddevgeeks.crashsink

import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class CrashHandlerManagerTest {

    private var original: Thread.UncaughtExceptionHandler? = null
    private lateinit var manager: CrashHandlerManager

    @Before fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        manager = CrashHandlerManager(CrashAttributor("com.example."), mock(CrashProcessor::class.java))
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test fun installPutsOurInterceptorInTheSlot() {
        manager.install()
        assertSame(manager.installed, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun installCapturesPreviousAsDelegate() {
        val host = mock(Thread.UncaughtExceptionHandler::class.java)
        Thread.setDefaultUncaughtExceptionHandler(host)
        manager.install()
        assertSame(host, manager.installed!!.previous)
    }

    @Test fun reassertIsNoOpWhenStillInSlot() {
        manager.install()
        val first = manager.installed
        manager.reassert()
        assertSame(first, manager.installed)
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun reassertRewrapsWhenDisplaced() {
        manager.install()
        val first = manager.installed
        // A library installs itself after us without delegating to us.
        val intruder = mock(Thread.UncaughtExceptionHandler::class.java)
        Thread.setDefaultUncaughtExceptionHandler(intruder)

        manager.reassert()

        assertNotSame(first, manager.installed)
        assertSame(manager.installed, Thread.getDefaultUncaughtExceptionHandler())
        assertSame(intruder, manager.installed!!.previous)
    }
}
