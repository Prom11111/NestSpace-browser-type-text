package com.coke.nestspace.plugin;

public final class PluginCommandResult {

    private final boolean successful;
    private final String message;
    private final String dataJson;
    private final String errorCode;

    public PluginCommandResult(
        boolean successful,
        String message,
        String dataJson,
        String errorCode
    ) {
        this.successful = successful;
        this.message = message == null ? "" : message;
        this.dataJson = dataJson == null ? "" : dataJson;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    public static PluginCommandResult success(String message, String dataJson) {
        return new PluginCommandResult(true, message, dataJson, "");
    }

    public static PluginCommandResult failure(String message, String errorCode) {
        return new PluginCommandResult(false, message, "", errorCode);
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getMessage() {
        return message;
    }

    public String getDataJson() {
        return dataJson;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
