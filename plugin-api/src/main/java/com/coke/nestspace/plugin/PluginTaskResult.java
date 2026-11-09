package com.coke.nestspace.plugin;

public final class PluginTaskResult {

    private final boolean successful;
    private final boolean cancelled;
    private final String message;
    private final String errorCode;

    public PluginTaskResult(
        boolean successful,
        boolean cancelled,
        String message,
        String errorCode
    ) {
        this.successful = successful;
        this.cancelled = cancelled;
        this.message = message == null ? "" : message;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    public static PluginTaskResult success(String message) {
        return new PluginTaskResult(true, false, message, "");
    }

    public static PluginTaskResult failure(String message, String errorCode) {
        return new PluginTaskResult(false, false, message, errorCode);
    }

    public static PluginTaskResult cancelled(String message) {
        return new PluginTaskResult(false, true, message, "CANCELLED");
    }

    public boolean isSuccessful() {
        return successful;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
