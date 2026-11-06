package com.voltbridge.business.domain.command;

/** Keeps persisted command failure summaries within the database column limit. */
public final class DeviceCommandFailureReason {

    public static final int MAX_LENGTH = 512;

    private DeviceCommandFailureReason() {
    }

    public static String normalize(String reason) {
        if (reason == null) {
            return null;
        }
        int codePointCount = reason.codePointCount(0, reason.length());
        if (codePointCount <= MAX_LENGTH) {
            return reason;
        }
        int end = reason.offsetByCodePoints(0, MAX_LENGTH);
        return reason.substring(0, end);
    }
}
