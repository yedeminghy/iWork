package dev.agentdesktop.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.host.bootstrap.BootstrapConfig;
import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.persistence.DatabaseUpgradeCoordinator;
import dev.agentdesktop.persistence.McpCatalogRepository;
import dev.agentdesktop.persistence.SkillInstallRepository;
import dev.agentdesktop.persistence.PermissionRepository;
import dev.agentdesktop.persistence.ProviderProfileRepository;
import dev.agentdesktop.mcp.McpClientManager;
import dev.agentdesktop.mcp.SdkMcpClientManager;
import dev.agentdesktop.runtime.MockModelProvider;
import dev.agentdesktop.runtime.ModelProvider;
import dev.agentdesktop.runtime.provider.ProviderCatalog;
import dev.agentdesktop.runtime.provider.ProviderRegistry;
import dev.agentdesktop.runtime.ToolGateway;
import dev.agentdesktop.runtime.HarnessRunService;
import dev.agentdesktop.runtime.permission.DefaultPermissionEngine;
import dev.agentdesktop.runtime.permission.PermissionEngine;
import dev.agentdesktop.runtime.permission.PermissionFingerprints;
import dev.agentdesktop.runtime.workspace.FileRootGuard;
import dev.agentdesktop.runtime.workspace.WorkspaceFileAdapter;
import dev.agentdesktop.runtime.skill.AgentScopeSkillAdapter;
import dev.agentdesktop.runtime.skill.DefaultSkillRuntimeBridge;
import dev.agentdesktop.runtime.skill.LocalSkillRegistry;
import dev.agentdesktop.runtime.skill.SkillScriptAdapter;
import dev.agentdesktop.runtime.skill.importing.DefaultSkillImportService;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.DependencyStatus;
import dev.agentdesktop.runtime.skill.importing.SkillImportService;
import dev.agentdesktop.host.skill.GitHubSourceClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class HostConfiguration {
    @Bean DatabaseUpgradeCoordinator databaseUpgradeCoordinator(BootstrapConfig bootstrap,ObjectMapper mapper) {
        return new DatabaseUpgradeCoordinator(bootstrap.appHome(),
                System.getProperty("agentdesktop.app-version", "0.1.0"),
                Integer.getInteger("agentdesktop.db-schema-max", 8), mapper);
    }

    @Bean LocalDatabase localDatabase(DatabaseUpgradeCoordinator coordinator) {
        return coordinator.prepareAndMigrate();
    }

    @Bean
    ConversationRepository conversationRepository(BootstrapConfig bootstrap, ObjectMapper objectMapper,LocalDatabase database) throws IOException {
        Path workspace = workspace(bootstrap);
        Files.createDirectories(workspace);
        ConversationRepository repository = new ConversationRepository(database, objectMapper);
        repository.initializeProfile(workspace, System.getProperty("user.name", "Local User"));
        return repository;
    }

    @Bean StartupRecovery startupRecovery(ConversationRepository repository) {
        return StartupRecovery.from(repository.reconcileUnfinishedTurns(java.time.Instant.now()));
    }

    @Bean McpCatalogRepository mcpCatalogRepository(ObjectMapper objectMapper,LocalDatabase database){
        return new McpCatalogRepository(database,objectMapper);
    }

    @Bean SkillInstallRepository skillInstallRepository(LocalDatabase database){return new SkillInstallRepository(database);}
    @Bean ProviderProfileRepository providerProfileRepository(LocalDatabase database,ObjectMapper mapper){return new ProviderProfileRepository(database,mapper);}

    @Bean ProviderCatalog providerCatalog(ObjectMapper mapper){
        Path install=Path.of(System.getProperty("agentdesktop.install-dir",".")).toAbsolutePath().normalize();
        Path catalog=install.resolve("provider-catalog.json").normalize();
        if(!catalog.getParent().equals(install))throw new dev.agentdesktop.runtime.provider.ProviderException("PROVIDER_CATALOG_INVALID");
        return ProviderCatalog.load(catalog,mapper);
    }
    @Bean ProviderRegistry providerRegistry(ProviderCatalog catalog){return new ProviderRegistry(catalog);}
    @Bean HarnessRunService harnessRunService(ProviderRegistry registry,ObjectMapper mapper){return new HarnessRunService(registry,mapper);}
    @Bean(destroyMethod="close") ProviderSecretSessionStore providerSecretSessionStore(){return new ProviderSecretSessionStore();}
    @Bean ProviderService providerService(ProviderRegistry registry,ProviderSecretSessionStore secrets,ProviderProfileRepository states){return new ProviderService(registry,secrets,states);}
    @Bean PermissionRepository permissionRepository(BootstrapConfig bootstrap,ObjectMapper mapper,LocalDatabase database,ConversationRepository conversations) throws IOException {
        PermissionRepository repository=new PermissionRepository(database,mapper);
        Path workspace=workspace(bootstrap).toRealPath();
        repository.ensureDefaultRoot(new dev.agentdesktop.persistence.PermissionStore.FileRootDraft(
                "root_default","ws_default","Default Workspace",workspace.toString(),
                PermissionFingerprints.sha256(workspace.toString()),PermissionFingerprints.sha256(workspace.toString()),
                "READ_WRITE",true,true,java.time.Instant.now()));
        return repository;
    }

    @Bean GitHubSourceClient gitHubSourceClient(ObjectMapper mapper){return new GitHubSourceClient(mapper);}

    @Bean LocalSkillRegistry localSkillRegistry(BootstrapConfig bootstrap,ObjectMapper mapper,LocalDatabase database){
        Path workspace=workspace(bootstrap);
        Path builtin=Path.of(System.getProperty("agentdesktop.builtin-skills","skills")).toAbsolutePath().normalize();
        LocalSkillRegistry registry=new LocalSkillRegistry(database,mapper,builtin,bootstrap.appHome(),workspace,"ws_default");
        registry.refreshAll();return registry;
    }
    @Bean AgentScopeSkillAdapter agentScopeSkillAdapter(LocalSkillRegistry registry){
        return new AgentScopeSkillAdapter(new DefaultSkillRuntimeBridge(registry,capability->!"pptx".equals(capability)||Boolean.getBoolean("agentdesktop.pptx-runtime-available")));
    }

    @Bean(destroyMethod="close") McpClientManager mcpClientManager(BootstrapConfig bootstrap,ObjectMapper mapper,McpCatalogRepository repository){
        Path workspace=workspace(bootstrap);
        SdkMcpClientManager manager=new SdkMcpClientManager(repository,mapper,workspace,Boolean.getBoolean("agentdesktop.test-fixtures"));
        Path install=Path.of(System.getProperty("agentdesktop.install-dir",".")).toAbsolutePath().normalize();
        InitialMcpCatalogSeeder.seed(install,mapper,repository,manager);
        return manager;
    }

    @Bean PermissionEngine permissionEngine(){return new DefaultPermissionEngine(Boolean.getBoolean("agentdesktop.test-fixtures"));}
    @Bean FileRootGuard fileRootGuard(PermissionRepository repository){return new FileRootGuard(repository);}
    @Bean WorkspaceFileAdapter workspaceFileAdapter(FileRootGuard guard){return new WorkspaceFileAdapter(guard);}
    @Bean SkillScriptAdapter skillScriptAdapter(AgentScopeSkillAdapter skills,FileRootGuard guard){return new SkillScriptAdapter(skills,guard);}
    @Bean ToolGateway toolGateway(McpClientManager manager,PermissionEngine permissions,ObjectMapper mapper,FileRootGuard guard,WorkspaceFileAdapter files,SkillScriptAdapter scripts){return new ToolGateway(manager,permissions,mapper,guard,files,scripts);}

    @Bean SkillImportService skillImportService(BootstrapConfig bootstrap,ObjectMapper mapper,
                                                 LocalSkillRegistry registry,SkillInstallRepository repository,
                                                 GitHubSourceClient gitHub,McpClientManager mcp){
        DefaultSkillImportService service=new DefaultSkillImportService(bootstrap.appHome(),mapper,registry,repository,gitHub,value->{
            var server=mcp.list(200,null).items().stream().filter(item->item.id().equals(value)||item.name().equals(value)).findFirst();
            if(server.isEmpty())return DependencyStatus.UNAVAILABLE;
            if(!server.get().enabled())return DependencyStatus.DISABLED;
            return "READY".equals(server.get().status())?DependencyStatus.AVAILABLE:DependencyStatus.UNAVAILABLE;
        });
        service.recover();
        return service;
    }

    @Bean
    ModelProvider modelProvider() {
        if(!Boolean.getBoolean("agentdesktop.test-fixtures"))
            return new ModelProvider(){public String id(){return "unavailable";}public reactor.core.publisher.Flux<dev.agentdesktop.runtime.ProviderEvent> stream(dev.agentdesktop.runtime.ChatRequest request){return reactor.core.publisher.Flux.error(new dev.agentdesktop.runtime.provider.ProviderException("PROFILE_NOT_AVAILABLE"));}};
        return new MockModelProvider(Duration.ofMillis(20), true);
    }

    private static Path workspace(BootstrapConfig bootstrap){
        String configured=System.getProperty("agentdesktop.workspace");
        Path value=(configured==null||configured.isBlank()?bootstrap.appHome().resolve("workspace"):Path.of(configured)).toAbsolutePath().normalize();
        try{Files.createDirectories(value);return value.toRealPath();}catch(IOException exception){throw new IllegalStateException("WORKSPACE_UNAVAILABLE",exception);}
    }
}
