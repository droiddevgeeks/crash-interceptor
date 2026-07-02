package com.droiddevgeeks.crashsink

import org.junit.After
import org.junit.Assert.assertFalse
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

    @Test fun installTwiceOnSameManagerDoesNotStack() {
        val host = mock(Thread.UncaughtExceptionHandler::class.java)
        Thread.setDefaultUncaughtExceptionHandler(host)

        manager.install()
        val first = manager.installed
        manager.install() // host double-init

        // Same single interceptor, still sitting directly over the original host — not wrapped
        // around a second crashsink interceptor.
        assertSame(first, manager.installed)
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
        assertSame(host, manager.installed!!.previous)
        assertFalse(manager.installed!!.previous is CrashInterceptor)
    }

    @Test fun secondManagerSamePrefixAdoptsInsteadOfStacking() {
        manager.install()
        val first = manager.installed

        // A second reporter with the SAME ownedPrefix (e.g. host called MySdk.init twice, each
        // building its own CrashReporter) installs — it must adopt, not stack.
        val second = CrashHandlerManager(CrashAttributor("com.example."), mock(CrashProcessor::class.java))
        second.install()

        assertSame(first, second.installed)
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
        assertFalse(Thread.getDefaultUncaughtExceptionHandler().let { it is CrashInterceptor && it.previous is CrashInterceptor })
    }

    @Test fun secondManagerDifferentPrefixChains() {
        manager.install()
        val first = manager.installed

        // A genuinely different guest SDK also using crashsink → legitimate chaining, not a dup.
        val other = CrashHandlerManager(CrashAttributor("com.other."), mock(CrashProcessor::class.java))
        other.install()

        assertNotSame(first, other.installed)
        assertSame(other.installed, Thread.getDefaultUncaughtExceptionHandler())
        assertSame(first, other.installed!!.previous)
    }
}
