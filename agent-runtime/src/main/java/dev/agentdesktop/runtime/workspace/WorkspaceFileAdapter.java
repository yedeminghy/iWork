package dev.agentdesktop.runtime.workspace;

import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionFingerprints;
import dev.agentdesktop.runtime.permission.PermissionModels.ExecutionPermit;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class WorkspaceFileAdapter {
    public static final int MAX_BYTES=1024*1024;
    private final FileRootGuard guard;
    public WorkspaceFileAdapter(FileRootGuard guard){this.guard=guard;}
    public FileResult read(FileRootGuard.ResolvedFile file,ExecutionPermit permit,String fingerprint){
        permit.consume(fingerprint);guard.revalidate(file);
        try{if(!Files.isRegularFile(file.target(),LinkOption.NOFOLLOW_LINKS))throw new PermissionException("FILE_PATH_INVALID");long size=Files.size(file.target());if(size>MAX_BYTES)throw new PermissionException("FILE_TOO_LARGE");byte[] bytes=Files.readAllBytes(file.target());String text;
            try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(Exception e){throw new PermissionException("FILE_NOT_UTF8");}
            if(text.indexOf('\0')>=0)throw new PermissionException("FILE_BINARY_UNSUPPORTED");
            guard.revalidate(file);return new FileResult(text,bytes.length,PermissionFingerprints.sha256(text),false);
        }catch(PermissionException e){throw e;}catch(Exception e){throw new PermissionException("FILE_ROOT_UNAVAILABLE");}
    }
    public FileResult write(FileRootGuard.ResolvedFile file,String content,boolean overwrite,ExecutionPermit permit,String fingerprint){
        if(content==null||content.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new PermissionException("FILE_TOO_LARGE");permit.consume(fingerprint);guard.revalidate(file);java.nio.file.Path temporary=null;
        try{if(Files.exists(file.target(),LinkOption.NOFOLLOW_LINKS)&&!overwrite)throw new PermissionException("FILE_ALREADY_EXISTS");temporary=Files.createTempFile(file.parent(),".agent-write-",".tmp");byte[] bytes=content.getBytes(StandardCharsets.UTF_8);try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.write(ByteBuffer.wrap(bytes));channel.force(true);}guard.revalidate(file);
            try{Files.move(temporary,file.target(),overwrite?new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING}:new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE});}catch(AtomicMoveNotSupportedException e){throw new PermissionException("FILE_PATH_CHANGED");}
            temporary=null;guard.revalidate(file);return new FileResult(null,bytes.length,PermissionFingerprints.sha256(content),true);
        }catch(PermissionException e){throw e;}catch(java.nio.file.FileAlreadyExistsException e){throw new PermissionException("FILE_ALREADY_EXISTS");}catch(Exception e){throw new PermissionException("FILE_ROOT_UNAVAILABLE");}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    public record FileResult(String modelContent,long byteSize,String contentHash,boolean written){}
}
