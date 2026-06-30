package com.droiddevgeeks.crashsink;

/** Decorator in the uncaught-exception chain. Captures our crashes, always delegates. */
public final class CrashInterceptor implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler previous;
    private final CrashAttributor attributor;
    private final CrashProcessor processor;

    public CrashInterceptor(final Thread.UncaughtExceptionHandler previous,
                            final CrashAttributor attributor,
                            final CrashProcessor processor) {
        this.previous = previous;
        this.attributor = attributor;
        this.processor = processor;
    }

    public Thread.UncaughtExceptionHandler getPrevious() {
        return previous;
    }

    @Override
    public void uncaughtException(final Thread thread, final Throwable throwable) {
        try {
            if (attributor.isOurs(throwable)) {
                processor.persistBlocking(thread, throwable);
            }
        } catch (Throwable suppressed) {
            // Our reporting must never mask the original crash.
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(10);
            }
        }
    }
}
