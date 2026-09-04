package dev.agentdesktop.host;

import dev.agentdesktop.host.bootstrap.BootstrapConfig;
import dev.agentdesktop.persistence.Ids;
import dev.agentdesktop.persistence.PermissionRepository;
import dev.agentdesktop.persistence.PermissionStore;
import dev.agentdesktop.runtime.permission.PermissionFingerprints;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Validates native-picker paths before they become capability upper bounds. */
@Service
public final class FileRootService {
    private final PermissionRepository repository;
    private final Path appHome;
    private final Path workspace;
    private final List<Path> protectedRoots;
    public FileRootService(PermissionRepository repository,BootstrapConfig bootstrap){
        this.repository=repository;this.appHome=bootstrap.appHome().toAbsolutePath().normalize();
        this.workspace=this.appHome.resolve("workspace").normalize();
        this.protectedRoots=protectedRoots();
    }
    public java.util.List<PermissionStore.FileRootSummary> list(String workspaceId){return repository.listRoots(workspaceId);}
    public PermissionStore.FileRootSummary add(String workspaceId,String selectedPath,String mode){
        if(!ConversationIds.workspace(workspaceId)||selectedPath==null||selectedPath.length()>4096)throw new PermissionRepository.DomainException("FILE_PATH_INVALID");
        if(!java.util.List.of("READ_ONLY","READ_WRITE").contains(mode))throw new PermissionRepository.DomainException("FILE_ROOT_MODE_INVALID");
        try{
            Path selected=Path.of(selectedPath);if(!selected.isAbsolute()||Files.isSymbolicLink(selected))throw new PermissionRepository.DomainException("FILE_PATH_LINK_UNSUPPORTED");
            Path canonical=selected.toRealPath(LinkOption.NOFOLLOW_LINKS);BasicFileAttributes attrs=Files.readAttributes(canonical,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attrs.isDirectory()||attrs.isOther())throw new PermissionRepository.DomainException("FILE_ROOT_UNAVAILABLE");
            Path canonicalApp=Files.exists(appHome)?appHome.toRealPath():appHome;Path canonicalWorkspace=Files.exists(workspace)?workspace.toRealPath():workspace;
            if((canonical.startsWith(canonicalApp)&&!canonical.equals(canonicalWorkspace))||canonicalApp.startsWith(canonical))throw new PermissionRepository.DomainException("FILE_ROOT_PROTECTED");
            for(Path protectedRoot:protectedRoots)if(canonical.startsWith(protectedRoot)||protectedRoot.startsWith(canonical))throw new PermissionRepository.DomainException("FILE_ROOT_PROTECTED");
            String label=canonical.getFileName()==null?"Selected Folder":canonical.getFileName().toString();String identity=String.valueOf(attrs.fileKey());
            return repository.addRoot(new PermissionStore.FileRootDraft(Ids.newId("root"),workspaceId,label,canonical.toString(),
                    PermissionFingerprints.sha256(canonical.toString()),PermissionFingerprints.sha256(identity),mode,true,false,Instant.now()));
        }catch(PermissionRepository.DomainException e){throw e;}catch(Exception e){throw new PermissionRepository.DomainException("FILE_ROOT_UNAVAILABLE");}
    }
    public PermissionStore.FileRootSummary update(String id,String mode,boolean enabled,long expectedVersion){return repository.updateRoot(id,mode,enabled,expectedVersion);}
    public void remove(String id,long expectedVersion){repository.removeRoot(id,expectedVersion);}
    private static List<Path> protectedRoots(){
        List<Path> roots=new ArrayList<>();
        addProtected(roots,System.getProperty("java.home"));
        addProtected(roots,System.getProperty("agentdesktop.install-dir"));
        addProtected(roots,System.getProperty("agentdesktop.builtin-skills"));
        try{
            Path code=Path.of(FileRootService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            addProtected(roots,Files.isRegularFile(code)?code.getParent():code);
        }catch(Exception ignored){}
        return List.copyOf(roots);
    }
    private static void addProtected(List<Path> roots,String value){
        if(value==null||value.isBlank())return;
        try{Path path=Path.of(value).toAbsolutePath().normalize();roots.add(Files.exists(path)?path.toRealPath():path);}catch(Exception ignored){}
    }
    private static void addProtected(List<Path> roots,Path path){if(path!=null)addProtected(roots,path.toString());}
    private static final class ConversationIds{private static boolean workspace(String id){return id!=null&&id.matches("ws_[A-Za-z0-9_-]{3,80}");}}
}
