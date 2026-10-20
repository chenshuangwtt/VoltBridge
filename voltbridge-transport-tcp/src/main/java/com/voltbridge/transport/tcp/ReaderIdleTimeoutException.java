package com.voltbridge.transport.tcp;

public final class ReaderIdleTimeoutException extends RuntimeException {

    public ReaderIdleTimeoutException() {
        super("TCP reader idle timeout", null, false, false);
    }
}
