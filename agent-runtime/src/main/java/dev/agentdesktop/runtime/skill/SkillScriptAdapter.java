package dev.agentdesktop.runtime.skill;

import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionModels.ExecutionPermit;
import dev.agentdesktop.runtime.skill.SkillModels.SkillScriptPlan;
import dev.agentdesktop.runtime.workspace.FileRootGuard;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes a hash-pinned script with an application-selected interpreter and no model-controlled command line. */
public final class SkillScriptAdapter {
    private static final int MAX_OUTPUT=512*1024;
    private final AgentScopeSkillAdapter skills;private final FileRootGuard roots;private final Duration timeout;
    private final AtomicInteger activeProcesses=new AtomicInteger();
    public SkillScriptAdapter(AgentScopeSkillAdapter skills,FileRootGuard roots){this(skills,roots,Duration.ofSeconds(30));}
    SkillScriptAdapter(AgentScopeSkillAdapter skills,FileRootGuard roots,Duration timeout){this.skills=skills;this.roots=roots;this.timeout=timeout;}

    public PreparedScript prepare(String turnId,String skillId,String resourceId,String workingRootId,List<String> arguments){
        validateArguments(arguments);SkillScriptPlan plan=skills.resolveScript(turnId,skillId,resourceId);FileRootGuard.RootDirectory root=roots.resolveRoot(workingRootId);
        return new PreparedScript(turnId,plan,root,List.copyOf(arguments),interpreter(plan.interpreterProfile()),false);
    }

    public PreparedScript prepareWorkspace(String turnId,String workingRootId,String relativePath,String expectedHash,List<String> arguments){
        validateArguments(arguments);FileRootGuard.ResolvedFile file=roots.resolve(workingRootId,relativePath,false);String profile=profile(relativePath,false);
        if(expectedHash==null||!expectedHash.matches("sha256:[0-9a-f]{64}")||!Files.isRegularFile(file.target()))throw new PermissionException("SCRIPT_STALE");
        String actual=hash(file.target());if(!actual.equals(expectedHash))throw new PermissionException("SCRIPT_STALE");
        rejectRuntimeDependencyInstaller(file.target());
        FileRootGuard.RootDirectory root=roots.resolveRoot(workingRootId);SkillScriptPlan plan=new SkillScriptPlan("workspace",relativePath,"工作区脚本",file.target(),actual,profile);
        return new PreparedScript(turnId,plan,root,List.copyOf(arguments),interpreter(profile),true);
    }

    public ScriptResult execute(PreparedScript prepared,ExecutionPermit permit,String fingerprint,dev.agentdesktop.mcp.McpModels.CancellationToken cancellation){
        SkillScriptPlan current;
        if(prepared.workspaceSource()){
            FileRootGuard.ResolvedFile file=roots.resolve(prepared.root().root().id(),prepared.plan().resourceId(),false);
            rejectRuntimeDependencyInstaller(file.target());
            current=new SkillScriptPlan("workspace",prepared.plan().resourceId(),"工作区脚本",file.target(),hash(file.target()),profile(file.relativePath(),false));
        }else current=skills.resolveScript(prepared.turnId(),prepared.plan().skillId(),prepared.plan().resourceId());
        FileRootGuard.RootDirectory root=roots.resolveRoot(prepared.root().root().id());
        if(!current.contentHash().equals(prepared.plan().contentHash())||!current.scriptPath().equals(prepared.plan().scriptPath())||!current.interpreterProfile().equals(prepared.plan().interpreterProfile())||root.root().version()!=prepared.root().root().version()||!root.identity().equals(prepared.root().identity()))throw new PermissionException("SCRIPT_STALE");
        permit.consume(fingerprint);List<String> command=new ArrayList<>();command.add(prepared.interpreter().executable().toString());command.addAll(prepared.interpreter().prefixArguments());command.add(prepared.plan().scriptPath().toString());command.addAll(prepared.arguments());
        Process process=null;try{ProcessBuilder builder=new ProcessBuilder(command);builder.directory(root.path().toFile());builder.environment().clear();builder.environment().putAll(prepared.interpreter().environment());process=builder.start();activeProcesses.incrementAndGet();Process running=process;
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){Future<byte[]> stdout=executor.submit(()->bounded(running.getInputStream()));Future<byte[]> stderr=executor.submit(()->bounded(running.getErrorStream()));long deadline=System.nanoTime()+timeout.toNanos();while(process.isAlive()){if(cancellation.isCancellationRequested()){terminate(process);throw new PermissionException("TOOL_CANCELLED");}if(System.nanoTime()>=deadline){terminate(process);throw new PermissionException("SCRIPT_TIMEOUT");}Thread.sleep(25);}byte[] out=get(stdout);byte[] err=get(stderr);String outText=safeOutput(out);String errText=redact(safeOutput(err));if(process.exitValue()!=0)return new ScriptResult(false,summary(errText),out.length,err.length,missingPptxRuntime(errText)?"PPTX_RUNTIME_UNAVAILABLE":"SCRIPT_FAILED");return new ScriptResult(true,summary(outText),out.length,err.length,null);}
        }catch(PermissionException e){throw e;}catch(OutputLimitException e){if(process!=null)terminate(process);throw new PermissionException("SCRIPT_OUTPUT_TOO_LARGE");}catch(InterruptedException e){Thread.currentThread().interrupt();if(process!=null)terminate(process);throw new PermissionException("TOOL_CANCELLED");}catch(Exception e){if(process!=null)terminate(process);throw new PermissionException("SCRIPT_EXECUTION_FAILED");}finally{if(process!=null)activeProcesses.decrementAndGet();}
    }

    public int activeProcessCount(){return activeProcesses.get();}

    private static Interpreter interpreter(String profile){
        Path systemRoot=trustedSystemRoot();Map<String,String> environment=baseEnvironment(systemRoot);
        return switch(profile){
            case "WINDOWS_POWERSHELL_5"->new Interpreter(trustedPowerShell(systemRoot),List.of("-NoLogo","-NoProfile","-NonInteractive","-File"),environment);
            case "NODE_JS"->{Path executable=trustedConfigured("agentdesktop.node-executable");if(Boolean.getBoolean("agentdesktop.node-electron"))environment.put("ELECTRON_RUN_AS_NODE","1");String modules=System.getProperty("agentdesktop.node-modules");if(modules!=null&&!modules.isBlank())environment.put("NODE_PATH",Path.of(modules).toAbsolutePath().normalize().toString());yield new Interpreter(executable,List.of(),environment);}
            case "PYTHON_3"->{String configured=System.getProperty("agentdesktop.python-executable");Path executable;List<String> prefix;if(configured!=null&&!configured.isBlank()){executable=trustedConfigured("agentdesktop.python-executable");prefix=List.of("-I");}else{try{executable=systemRoot.resolve("py.exe").toRealPath();}catch(Exception e){throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");}prefix=List.of("-3","-I");}copyEnvironment(environment,"LOCALAPPDATA","APPDATA","USERPROFILE","TEMP","TMP");yield new Interpreter(executable,prefix,environment);}
            default->throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");
        };
    }
    private static String profile(String path,boolean allowPowerShell){String lower=path==null?"":path.toLowerCase(java.util.Locale.ROOT);if(lower.endsWith(".js"))return "NODE_JS";if(lower.endsWith(".py"))return "PYTHON_3";if(allowPowerShell&&lower.endsWith(".ps1"))return "WINDOWS_POWERSHELL_5";throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");}
    private static Map<String,String> baseEnvironment(Path systemRoot){Map<String,String> result=new LinkedHashMap<>();result.put("SystemRoot",systemRoot.toString());result.put("WINDIR",systemRoot.toString());result.put("PATH",systemRoot.resolve("System32").toString());result.put("NO_COLOR","1");return result;}
    private static void copyEnvironment(Map<String,String> target,String... names){for(String name:names){String value=System.getenv(name);if(value!=null&&!value.isBlank())target.put(name,value);}}
    private static Path trustedConfigured(String property){String value=System.getProperty(property);if(value==null||value.isBlank())throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");try{Path path=Path.of(value).toRealPath();if(!path.isAbsolute()||!Files.isRegularFile(path))throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");return path;}catch(PermissionException e){throw e;}catch(Exception e){throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");}}
    private static Path trustedSystemRoot(){String value=System.getenv("SystemRoot");if(value==null||value.isBlank())throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");try{Path root=Path.of(value).toRealPath();if(!root.isAbsolute()||!Files.isDirectory(root))throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");return root;}catch(PermissionException e){throw e;}catch(Exception e){throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");}}
    private static Path trustedPowerShell(Path systemRoot){try{Path executable=systemRoot.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").toRealPath();if(!Files.isRegularFile(executable)||!executable.startsWith(systemRoot))throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");return executable;}catch(PermissionException e){throw e;}catch(Exception e){throw new PermissionException("SCRIPT_INTERPRETER_UNAVAILABLE");}}
    private static String hash(Path path){try(InputStream input=Files.newInputStream(path)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))>=0)digest.update(buffer,0,read);return "sha256:"+HexFormat.of().formatHex(digest.digest());}catch(Exception e){throw new PermissionException("SCRIPT_STALE");}}
    private static void rejectRuntimeDependencyInstaller(Path path){
        try{
            if(Files.size(path)>1024L*1024L)throw new PermissionException("SCRIPT_STALE");
            String source=Files.readString(path,StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
            if(source.matches("(?s).*\\bpip\\b.{0,160}\\binstall\\b.*")||source.matches("(?s).*\\bnpm\\b.{0,160}\\b(?:install|i)\\b.*"))
                throw new PermissionException("RUNTIME_DEPENDENCY_INSTALL_DENIED");
        }catch(PermissionException exception){throw exception;}
        catch(Exception exception){throw new PermissionException("SCRIPT_STALE");}
    }
    private static byte[] get(Future<byte[]> future)throws Exception{try{return future.get();}catch(ExecutionException e){if(e.getCause() instanceof OutputLimitException limit)throw limit;throw e;}}
    private static void validateArguments(List<String> arguments){if(arguments==null||arguments.size()>32)throw new PermissionException("TOOL_ARGUMENTS_INVALID");int total=0;for(String value:arguments){if(value==null||value.length()>1024||value.indexOf('\0')>=0)throw new PermissionException("TOOL_ARGUMENTS_INVALID");total+=value.length();}if(total>8192)throw new PermissionException("TOOL_ARGUMENTS_INVALID");}
    private static byte[] bounded(InputStream stream)throws Exception{try(stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int read,total=0;while((read=stream.read(buffer))>=0){total+=read;if(total>MAX_OUTPUT)throw new OutputLimitException();out.write(buffer,0,read);}return out.toByteArray();}}
    private static String safeOutput(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]"," ");}
    private static String redact(String value){return value.replaceAll("(?i)(authorization|cookie|token|secret|password)\\s*[:=]\\s*\\S+","$1=[REDACTED]");}
    private static String summary(String value){String clean=value.strip();return clean.substring(0,Math.min(clean.length(),4096));}
    private static boolean missingPptxRuntime(String value){return value.matches("(?s).*Cannot find module ['\"]pptxgenjs['\"].*");}
    private static void terminate(Process process){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
    public record Interpreter(Path executable,List<String> prefixArguments,Map<String,String> environment){public Interpreter{prefixArguments=List.copyOf(prefixArguments);environment=Map.copyOf(environment);}}
    public record PreparedScript(String turnId,SkillScriptPlan plan,FileRootGuard.RootDirectory root,List<String> arguments,Interpreter interpreter,boolean workspaceSource){}
    public record ScriptResult(boolean succeeded,String summary,long stdoutBytes,long stderrBytes,String errorCode){}
    private static final class OutputLimitException extends RuntimeException{}
}
