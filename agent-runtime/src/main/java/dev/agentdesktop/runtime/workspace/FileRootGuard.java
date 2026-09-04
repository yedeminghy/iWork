package dev.agentdesktop.runtime.workspace;

import dev.agentdesktop.persistence.PermissionStore;
import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionFingerprints;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/** Resolves rootId + protocol-relative path without following links. */
public final class FileRootGuard {
    public static final String DEFAULT_ROOT_ID="root_default";
    private static final Set<String> RESERVED=Set.of("CON","PRN","AUX","NUL","COM1","COM2","COM3","COM4","COM5","COM6","COM7","COM8","COM9","LPT1","LPT2","LPT3","LPT4","LPT5","LPT6","LPT7","LPT8","LPT9");
    private static final Set<String> DEFAULT_ROOT_ALIASES=Set.of("default","workspace","ws_default");
    private final PermissionStore roots;
    public FileRootGuard(PermissionStore roots){this.roots=roots;}

    public ResolvedFile resolve(String rootId,String relativePath,boolean write){
        PermissionStore.FileRootRecord root=requireRoot(rootId);if(!root.enabled())throw new PermissionException("FILE_ROOT_UNAVAILABLE");
        if(write&&!"READ_WRITE".equals(root.mode()))throw new PermissionException("FILE_ROOT_MODE_DENIED");
        validateRelative(relativePath);
        try{
            Path canonicalRoot=Path.of(root.canonicalPath()).toRealPath(LinkOption.NOFOLLOW_LINKS);BasicFileAttributes rootAttrs=attributes(canonicalRoot);
            if(!rootAttrs.isDirectory()||rootAttrs.isOther()||Files.isSymbolicLink(canonicalRoot))throw new PermissionException("FILE_ROOT_UNAVAILABLE");
            Path target=canonicalRoot.resolve(relativePath.replace('/',java.io.File.separatorChar)).normalize();if(!target.startsWith(canonicalRoot))throw new PermissionException("FILE_PATH_OUT_OF_SCOPE");
            Path cursor=canonicalRoot;for(Path part:canonicalRoot.relativize(target)){cursor=cursor.resolve(part);if(!Files.exists(cursor,LinkOption.NOFOLLOW_LINKS))break;BasicFileAttributes attrs=attributes(cursor);if(Files.isSymbolicLink(cursor)||attrs.isOther())throw new PermissionException("FILE_PATH_LINK_UNSUPPORTED");Path real=cursor.toRealPath(LinkOption.NOFOLLOW_LINKS);if(!real.startsWith(canonicalRoot))throw new PermissionException("FILE_PATH_OUT_OF_SCOPE");}
            Path parent=target.getParent();if(parent==null||!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))throw new PermissionException("FILE_PATH_INVALID");BasicFileAttributes parentAttrs=attributes(parent);if(!parentAttrs.isDirectory()||parentAttrs.isOther()||Files.isSymbolicLink(parent))throw new PermissionException("FILE_PATH_LINK_UNSUPPORTED");
            return new ResolvedFile(root,target,parent,relativePath,identity(rootAttrs),identity(parentAttrs));
        }catch(PermissionException e){throw e;}catch(Exception e){throw new PermissionException("FILE_ROOT_UNAVAILABLE");}
    }
    public RootDirectory resolveRoot(String rootId){PermissionStore.FileRootRecord root=requireRoot(rootId);if(!root.enabled())throw new PermissionException("FILE_ROOT_UNAVAILABLE");try{Path path=Path.of(root.canonicalPath()).toRealPath(LinkOption.NOFOLLOW_LINKS);BasicFileAttributes attrs=attributes(path);if(!attrs.isDirectory()||attrs.isOther()||Files.isSymbolicLink(path))throw new PermissionException("FILE_ROOT_UNAVAILABLE");return new RootDirectory(root,path,identity(attrs));}catch(PermissionException e){throw e;}catch(Exception e){throw new PermissionException("FILE_ROOT_UNAVAILABLE");}}
    public void revalidate(ResolvedFile resolved){ResolvedFile current=resolve(resolved.root().id(),resolved.relativePath(),"READ_WRITE".equals(resolved.root().mode()));if(current.root().version()!=resolved.root().version()||!current.rootIdentity().equals(resolved.rootIdentity())||!current.parentIdentity().equals(resolved.parentIdentity()))throw new PermissionException("FILE_PATH_CHANGED");}
    private static BasicFileAttributes attributes(Path path)throws Exception{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    private static String identity(BasicFileAttributes attrs){return String.valueOf(attrs.fileKey())+'\0'+attrs.creationTime().toMillis();}
    private PermissionStore.FileRootRecord requireRoot(String requestedRootId){
        if(requestedRootId==null||requestedRootId.isBlank())throw new PermissionException("FILE_ROOT_NOT_FOUND");
        String normalized=DEFAULT_ROOT_ALIASES.contains(requestedRootId.toLowerCase(Locale.ROOT))?DEFAULT_ROOT_ID:requestedRootId;
        try{return roots.getRoot(normalized);}catch(RuntimeException error){
            if("FILE_ROOT_NOT_FOUND".equals(error.getMessage()))throw new PermissionException("FILE_ROOT_NOT_FOUND");
            throw error;
        }
    }
    static void validateRelative(String path){
        if(path==null||path.isBlank()||path.length()>1024||path.startsWith("/")||path.startsWith("\\")||path.matches("^[A-Za-z]:.*")||path.indexOf('\0')>=0||path.chars().anyMatch(c->c<32))throw new PermissionException("FILE_PATH_INVALID");
        String[] parts=path.split("/",-1);if(parts.length>32)throw new PermissionException("FILE_PATH_INVALID");for(String part:parts){String upper=part.toUpperCase(Locale.ROOT);String base=upper.contains(".")?upper.substring(0,upper.indexOf('.')):upper;if(part.isBlank()||part.equals(".")||part.equals("..")||part.contains(":")||part.endsWith(".")||part.endsWith(" ")||RESERVED.contains(base))throw new PermissionException("FILE_PATH_INVALID");}
    }
    public record ResolvedFile(PermissionStore.FileRootRecord root,Path target,Path parent,String relativePath,String rootIdentity,String parentIdentity){public String scopeFingerprint(){return PermissionFingerprints.sha256(root.id()+"\0"+root.mode()+"\0"+root.version()+"\0"+root.pathHash()+"\0"+rootIdentity);}}
    public record RootDirectory(PermissionStore.FileRootRecord root,Path path,String identity){public String scopeFingerprint(){return PermissionFingerprints.sha256(root.id()+"\0"+root.version()+"\0"+root.pathHash()+"\0"+identity);}}
}
