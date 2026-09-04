package dev.agentdesktop.runtime.permission;

public final class PermissionException extends RuntimeException {
    private final String code;
    public PermissionException(String code){super(code);this.code=code;}
    public String code(){return code;}
}
