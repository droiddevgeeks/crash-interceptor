package com.droiddevgeeks.crashsink;

import java.util.regex.Pattern;

/** Strips known-sensitive substrings from exception messages before persistence. */
public final class Redactor {

    private static final String MASK = "[REDACTED]";

    // 13-19 digit card numbers, optionally separated by single spaces or dashes.
    private static final Pattern PAN =
            Pattern.compile("\\b[0-9](?:[ -]?[0-9]){12,18}\\b");
    // Bearer / auth tokens.
    private static final Pattern BEARER =
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    // UPI VPA: handle@psp
    private static final Pattern VPA =
            Pattern.compile("\\b[A-Za-z0-9.\\-_]{2,256}@[A-Za-z]{2,64}\\b");

    public String scrub(final String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String out = PAN.matcher(message).replaceAll(MASK);
        out = BEARER.matcher(out).replaceAll(MASK);
        out = VPA.matcher(out).replaceAll(MASK);
        return out;
    }
}
