package dev.agentdesktop.mcp;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import io.modelcontextprotocol.spec.McpSchema;

/** Forces the SDK process builder to use the validated workspace and an explicit minimal environment. */
final class RestrictedStdioClientTransport extends StdioClientTransport {
    private final Path workingDirectory;
    private final Map<String,String> environment;
    private final AtomicBoolean exited=new AtomicBoolean();
    RestrictedStdioClientTransport(ServerParameters parameters,McpJsonMapper mapper,Path workingDirectory,Map<String,String> environment){
        super(parameters,mapper);this.workingDirectory=workingDirectory;this.environment=Map.copyOf(environment);
    }
    @Override protected ProcessBuilder getProcessBuilder(){
        ProcessBuilder builder=super.getProcessBuilder();builder.directory(workingDirectory.toFile());builder.redirectErrorStream(false);
        builder.environment().clear();builder.environment().putAll(environment);return builder;
    }
    @Override public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>,Mono<McpSchema.JSONRPCMessage>> handler){
        return super.connect(handler).doOnSuccess(ignored->Thread.ofVirtual().name("mcp-stdio-exit-watch").start(()->{try{super.awaitForExit();}finally{exited.set(true);}}));
    }
    boolean hasExited(){return exited.get();}
}
