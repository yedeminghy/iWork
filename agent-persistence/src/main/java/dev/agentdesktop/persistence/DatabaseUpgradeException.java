package dev.agentdesktop.persistence;

public final class DatabaseUpgradeException extends IllegalStateException {
    private final String code;

    public DatabaseUpgradeException(String code) {
        super(code);
        this.code = code;
    }

    public DatabaseUpgradeException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
