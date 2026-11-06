package com.voltbridge.business.application.command;

import com.voltbridge.common.exception.VoltBridgeException;
import org.slf4j.Logger;

import java.util.UUID;

/** Builds safe command failure summaries and logs unexpected failures with their full cause. */
public final class DeviceCommandFailure {

    private static final String UNEXPECTED_FAILURE_REASON = "Unexpected internal error";

    private DeviceCommandFailure() {
    }

    public static String code(Throwable failure, String fallbackCode) {
        return failure instanceof VoltBridgeException voltBridgeException
                ? voltBridgeException.code() : fallbackCode;
    }

    public static String reason(Throwable failure) {
        return failure instanceof VoltBridgeException voltBridgeException
                ? voltBridgeException.getMessage() : UNEXPECTED_FAILURE_REASON;
    }

    public static void log(Logger logger, String operation, UUID commandId,
                           String code, Throwable failure) {
        if (failure instanceof VoltBridgeException voltBridgeException) {
            logger.warn("{}: commandId={}, code={}, reason={}", operation, commandId,
                    voltBridgeException.code(), voltBridgeException.getMessage());
            return;
        }
        logger.error("{}: commandId={}, code={}", operation, commandId, code, failure);
    }
}
