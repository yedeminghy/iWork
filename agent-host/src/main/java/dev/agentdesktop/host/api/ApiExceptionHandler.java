package dev.agentdesktop.host.api;

import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.mcp.McpException;
import dev.agentdesktop.persistence.McpCatalogRepository;
import dev.agentdesktop.persistence.PermissionRepository;
import dev.agentdesktop.runtime.skill.SkillException;
import dev.agentdesktop.runtime.skill.importing.SkillImportException;
import dev.agentdesktop.runtime.provider.ProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(ProviderException.class)
    org.springframework.http.ResponseEntity<Map<String,String>> provider(ProviderException exception){
        HttpStatus status=switch(exception.code()){
            case "PROFILE_NOT_AVAILABLE","MODEL_NOT_AVAILABLE"->HttpStatus.NOT_FOUND;
            case "PROVIDER_RATE_LIMITED","PROVIDER_QUOTA_EXCEEDED"->HttpStatus.TOO_MANY_REQUESTS;
            case "PROVIDER_KEY_MISSING","PROVIDER_AUTH_FAILED"->HttpStatus.UNAUTHORIZED;
            default->HttpStatus.BAD_REQUEST;
        };
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("code",exception.code(),"message","Provider request cannot be completed"));
    }
    @ExceptionHandler(PermissionRepository.DomainException.class)
    org.springframework.http.ResponseEntity<Map<String,String>> permission(PermissionRepository.DomainException exception){
        HttpStatus status=switch(exception.code()){case "APPROVAL_NOT_FOUND","FILE_ROOT_NOT_FOUND"->HttpStatus.NOT_FOUND;case "APPROVAL_EXPIRED"->HttpStatus.GONE;case "APPROVAL_ALREADY_RESOLVED","APPROVAL_STALE","APPROVAL_INVALIDATED","FILE_ROOT_STALE","FILE_ROOT_CONFLICT"->HttpStatus.CONFLICT;default->HttpStatus.BAD_REQUEST;};
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("code",exception.code(),"message","Permission request cannot be completed"));
    }
    @ExceptionHandler(SkillImportException.class)
    org.springframework.http.ResponseEntity<Map<String,String>> skillImport(SkillImportException exception){
        HttpStatus status=switch(exception.code()){
            case "SKILL_IMPORT_GITHUB_NOT_FOUND"->HttpStatus.NOT_FOUND;
            case "SKILL_IMPORT_GITHUB_RATE_LIMITED","SKILL_IMPORT_BUSY"->HttpStatus.TOO_MANY_REQUESTS;
            case "SKILL_IMPORT_EXPIRED"->HttpStatus.GONE;
            case "SKILL_IMPORT_STALE","SKILL_INSTALL_CONFLICT"->HttpStatus.CONFLICT;
            default->HttpStatus.BAD_REQUEST;
        };
        java.util.LinkedHashMap<String,String> body=new java.util.LinkedHashMap<>();
        body.put("code",exception.code());body.put("message","Skill import request cannot be completed");
        if(exception.detailCode()!=null)body.put("detailCode",exception.detailCode());
        return org.springframework.http.ResponseEntity.status(status).body(Map.copyOf(body));
    }
    @ExceptionHandler(SkillException.class)
    org.springframework.http.ResponseEntity<Map<String,String>> skill(SkillException exception){
        HttpStatus status=switch(exception.code()){case "SKILL_NOT_FOUND","SKILL_RESOURCE_NOT_FOUND"->HttpStatus.NOT_FOUND;case "SKILL_STALE","SKILL_DISABLED","SKILL_SHADOWED"->HttpStatus.CONFLICT;default->HttpStatus.BAD_REQUEST;};
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("code",exception.code(),"message","Skill request cannot be completed"));
    }
    @ExceptionHandler(McpException.class)
    org.springframework.http.ResponseEntity<Map<String,String>> mcp(McpException exception){
        HttpStatus status="MCP_SERVER_NOT_FOUND".equals(exception.code())?HttpStatus.NOT_FOUND:HttpStatus.BAD_REQUEST;
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("code",exception.code(),"message",exception.getMessage()));
    }
    @ExceptionHandler(McpCatalogRepository.DomainException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String,String> mcpRepository(McpCatalogRepository.DomainException exception){return Map.of("code",exception.code(),"message","MCP server was not found");}
    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalidRequest() {
        return Map.of("code", "INVALID_REQUEST", "message", "Request does not match the protocol schema");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalidArgument() {
        return Map.of("code", "INVALID_REQUEST", "message", "Request contains an unsupported value");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(IllegalStateException exception) {
        return Map.of("code", "TURN_CONFLICT", "message", exception.getMessage());
    }

    @ExceptionHandler(ConversationRepository.DomainException.class)
    org.springframework.http.ResponseEntity<Map<String, String>> domain(ConversationRepository.DomainException exception) {
        String code = exception.code();
        HttpStatus status = switch (code) {
            case "THREAD_NOT_FOUND", "TURN_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TURN_CONFLICT", "THREAD_BUSY", "THREAD_ARCHIVED", "EVENT_CONFLICT", "TURN_NOT_ACTIVE" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("code", code, "message", "Request cannot be completed"));
    }
}
