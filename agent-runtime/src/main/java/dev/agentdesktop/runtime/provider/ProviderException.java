package dev.agentdesktop.runtime.provider;

/** Carries only a stable, renderer-safe provider error code. */
public final class ProviderException extends RuntimeException {
    private final String code;

    public ProviderException(String code) {
        super(code);
        this.code = code;
    }

    public ProviderException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() { return code; }
}
