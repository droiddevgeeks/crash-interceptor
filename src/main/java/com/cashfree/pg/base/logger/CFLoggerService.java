package com.cashfree.pg.base.logger;

/**
 * Standalone stand-in for the real SDK logger (com.cashfree.pg.base.logger.CFLoggerService).
 * Mirrors the production API surface used by the crash subsystem so that code written here
 * ports back to the cf-analytics Android module unchanged. Logs to stderr in this project.
 */
public final class CFLoggerService {

    private static final CFLoggerService INSTANCE = new CFLoggerService();

    private CFLoggerService() {
    }

    public static CFLoggerService getInstance() {
        return INSTANCE;
    }

    public void e(final String tag, final String message) {
        System.err.println("E/" + tag + ": " + message);
    }
}
