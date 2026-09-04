package dev.agentdesktop.runtime.skill;

public final class SkillException extends RuntimeException {
    private final String code;

    public SkillException(String code) {
        super(code);
        this.code = code;
    }

    public SkillException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() { return code; }
}
