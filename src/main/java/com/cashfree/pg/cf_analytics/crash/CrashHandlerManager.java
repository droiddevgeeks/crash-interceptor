package com.cashfree.pg.cf_analytics.crash;

import com.cashfree.pg.base.logger.CFLoggerService;

/** Owns installation and survival of the crash interceptor in the handler chain. */
public final class CrashHandlerManager {

    private static final String TAG = "CrashHandlerManager";

    private final CrashAttributor attributor;
    private final CrashProcessor processor;
    private volatile CrashInterceptor installed;

    public CrashHandlerManager(final CrashAttributor attributor, final CrashProcessor processor) {
        this.attributor = attributor;
        this.processor = processor;
    }

    public synchronized void install() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        installed = new CrashInterceptor(previous, attributor, processor);
        Thread.setDefaultUncaughtExceptionHandler(installed);
    }

    public synchronized void reassert() {
        final Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current == installed) {
            return; // still in the slot; nothing to do
        }
        CFLoggerService.getInstance().e(TAG,
                "crash handler displaced; re-asserting over " + current);
        installed = new CrashInterceptor(current, attributor, processor);
        Thread.setDefaultUncaughtExceptionHandler(installed);
    }

    public CrashInterceptor getInstalled() {
        return installed;
    }
}
