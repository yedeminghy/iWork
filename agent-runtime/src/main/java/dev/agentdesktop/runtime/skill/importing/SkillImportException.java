package dev.agentdesktop.runtime.skill.importing;

public final class SkillImportException extends RuntimeException {
    private final String code;
    private final String detailCode;

    public SkillImportException(String code) { this(code, null, null); }
    public SkillImportException(String code, String detailCode) { this(code, detailCode, null); }
    public SkillImportException(String code, Throwable cause) { this(code, null, cause); }

    public SkillImportException(String code, String detailCode, Throwable cause) {
        super(code, cause, false, false);
        this.code = code;
        this.detailCode = detailCode;
    }

    public String code() { return code; }
    public String detailCode() { return detailCode; }
}
