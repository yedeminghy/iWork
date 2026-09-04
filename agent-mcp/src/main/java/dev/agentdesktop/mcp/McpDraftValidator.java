package dev.agentdesktop.mcp;

import dev.agentdesktop.mcp.McpModels.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class McpDraftValidator {
    private static final Set<String> SHELLS=Set.of("cmd.exe","cmd","powershell.exe","powershell","pwsh.exe","pwsh","sh","bash","zsh");
    private McpDraftValidator(){}
    static void validate(McpServerDraft draft,Path workspace,boolean fixtures){
        if(draft==null)fail(); int name=draft.name()==null?0:draft.name().codePointCount(0,draft.name().length());if(name<1||name>80)fail();
        ReconnectPolicy policy=draft.reconnectPolicy();if(policy==null||policy.maxAttempts()<0||policy.maxAttempts()>10||policy.initialBackoff().isNegative()||policy.maxBackoff().compareTo(policy.initialBackoff())<0)fail();
        if(draft instanceof StdioServerDraft stdio){
            Path executable=path(stdio.executable());if(!Files.isRegularFile(executable)||SHELLS.contains(executable.getFileName().toString().toLowerCase(Locale.ROOT)))fail();
            if(stdio.arguments()==null||stdio.arguments().size()>64||stdio.arguments().stream().anyMatch(v->v==null||v.length()>4096))fail();
            Path working=stdio.workingDirectory()==null?workspace:stdio.workingDirectory().toAbsolutePath().normalize();if(!working.startsWith(workspace)||!Files.isDirectory(working))fail();
            List<String> names=stdio.allowedEnvironmentNames()==null?List.of():stdio.allowedEnvironmentNames();if(names.size()>64||names.stream().anyMatch(v->!v.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")))fail();
        } else if(draft instanceof StreamableHttpServerDraft http){validateUri(http.endpoint());} else fail();
    }
    private static Path path(String raw){try{Path path=Path.of(raw);if(!path.isAbsolute())fail();return path.toRealPath();}catch(Exception e){throw new McpException("MCP_CONFIG_INVALID","Invalid MCP server configuration");}}
    private static void validateUri(URI uri){if(uri==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getHost()==null||(!"http".equals(uri.getScheme())&&!"https".equals(uri.getScheme())))fail();}
    private static void fail(){throw new McpException("MCP_CONFIG_INVALID","Invalid MCP server configuration");}
}
