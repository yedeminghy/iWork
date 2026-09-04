package dev.agentdesktop.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SQLite implementation. Approval/Turn/Item/Audit transitions are committed together. */
public final class PermissionRepository implements PermissionStore {
    private static final List<String> DECISIONS = List.of("ALLOW_ONCE", "ALLOW_THREAD", "DENY");
    private final LocalDatabase database;
    private final ObjectMapper mapper;

    public PermissionRepository(LocalDatabase database, ObjectMapper mapper) {
        this.database = Objects.requireNonNull(database);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override public synchronized ApprovalRequested requestApproval(ApprovalDraft draft) {
        requireDraft(draft);
        return transaction(connection -> {
            ApprovalSummary existing = findApprovalByCall(connection, draft.callId());
            if (existing != null) {
                if (!existing.requestFingerprint().equals(draft.requestFingerprint())) throw new DomainException("APPROVAL_STALE");
                return new ApprovalRequested(existing, requiredSequence(connection, existing.id()), true);
            }
            if (!"RUNNING".equals(turnStatus(connection, draft.turnId()))) throw new DomainException("TURN_NOT_ACTIVE");
            String requestJson = json(draft.safeRequest());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO approval(id,turn_id,capability,request_json,decision,expires_at,thread_id,
                      call_id,tool_id,capability_class,risk_level,request_fingerprint,subject_fingerprint,
                      arguments_hash,resource_scope_hash,created_at)
                    VALUES(?,?,?,?, 'PENDING', ?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                int i=1; statement.setString(i++,draft.id()); statement.setString(i++,draft.turnId());
                statement.setString(i++,draft.capabilityClass()); statement.setString(i++,requestJson);
                statement.setString(i++,draft.expiresAt().toString()); statement.setString(i++,draft.threadId());
                statement.setString(i++,draft.callId()); statement.setString(i++,draft.toolId());
                statement.setString(i++,draft.capabilityClass()); statement.setString(i++,draft.riskLevel());
                statement.setString(i++,draft.requestFingerprint()); statement.setString(i++,draft.subjectFingerprint());
                statement.setString(i++,draft.argumentsHash()); statement.setString(i++,draft.resourceScopeHash());
                statement.setString(i,draft.createdAt().toString()); statement.executeUpdate();
            }
            execute(connection,"UPDATE turn SET status='WAITING_APPROVAL' WHERE id=? AND status='RUNNING'",draft.turnId());
            long sequence = lastSequence(connection,draft.turnId())+1;
            insertItem(connection,Ids.newId("itm"),draft.threadId(),draft.turnId(),sequence,"APPROVAL","USER",
                    Map.of("type","CUSTOM approval.required","value",draft.safeRequest()),draft.createdAt());
            audit(connection,draft,"ASK","PENDING",null,draft.createdAt());
            execute(connection,"UPDATE thread SET updated_at=? WHERE id=?",draft.createdAt().toString(),draft.threadId());
            return new ApprovalRequested(findApproval(connection,draft.id()),sequence,false);
        });
    }

    @Override public synchronized ApprovalResolution resolveApproval(String approvalId, String decision,
                                                                      String expectedFingerprint, Instant now) {
        if (!DECISIONS.contains(decision)) throw new DomainException("INVALID_APPROVAL_DECISION");
        ApprovalResolution result=transaction(connection -> {
            ApprovalSummary approval = requireApproval(connection,approvalId);
            if (!approval.requestFingerprint().equals(expectedFingerprint)) throw new DomainException("APPROVAL_STALE");
            if (!"PENDING".equals(approval.decision())) {
                if (approval.decision().equals(decision)) return new ApprovalResolution(approval,resolvedSequence(connection,approval.id()),true);
                throw new DomainException("APPROVAL_ALREADY_RESOLVED");
            }
            if (!now.isBefore(approval.expiresAt())) {
                return terminalApproval(connection,approval,"EXPIRED","APPROVAL_EXPIRED",now,true);
            }
            if (!"WAITING_APPROVAL".equals(turnStatus(connection,approval.turnId()))) throw new DomainException("APPROVAL_INVALIDATED");
            execute(connection,"UPDATE approval SET decision=?,resolved_at=?,resolved_by_user_id=? WHERE id=? AND decision='PENDING'",
                    decision,now.toString(),ConversationRepository.LOCAL_USER_ID,approvalId);
            execute(connection,"UPDATE turn SET status='RUNNING' WHERE id=? AND status='WAITING_APPROVAL'",approval.turnId());
            long sequence=lastSequence(connection,approval.turnId())+1;
            ApprovalSummary resolved=findApproval(connection,approvalId);
            Map<String,Object> value=resolutionValue(resolved);
            insertItem(connection,Ids.newId("itm"),approval.threadId(),approval.turnId(),sequence,"APPROVAL","USER",
                    Map.of("type","CUSTOM approval.resolved","value",value),now);
            audit(connection,toDraft(approval),"RESOLVE",decision,null,now);
            execute(connection,"UPDATE thread SET updated_at=? WHERE id=?",now.toString(),approval.threadId());
            return new ApprovalResolution(resolved,sequence,false);
        });
        if("EXPIRED".equals(result.approval().decision()))throw new DomainException("APPROVAL_EXPIRED");
        return result;
    }

    @Override public synchronized ApprovalResolution expireApproval(String approvalId, Instant now) {
        return transaction(connection -> {
            ApprovalSummary approval=requireApproval(connection,approvalId);
            if (!"PENDING".equals(approval.decision())) return new ApprovalResolution(approval,resolvedSequence(connection,approval.id()),true);
            return terminalApproval(connection,approval,"EXPIRED","APPROVAL_EXPIRED",now,true);
        });
    }

    @Override public synchronized ApprovalResolution cancelPendingForTurn(String turnId, String code, Instant now) {
        return transaction(connection -> {
            ApprovalSummary approval=findPendingForTurn(connection,turnId);
            if (approval==null) return null;
            return terminalApproval(connection,approval,"CANCELLED",code,now,false);
        });
    }

    @Override public List<ApprovalSummary> pendingForTurn(String turnId) {
        try(Connection c=database.open();PreparedStatement s=c.prepareStatement("SELECT * FROM approval WHERE turn_id=? AND decision='PENDING' ORDER BY created_at")){
            s.setString(1,turnId);try(ResultSet r=s.executeQuery()){List<ApprovalSummary> result=new ArrayList<>();while(r.next())result.add(approval(r));return List.copyOf(result);}
        }catch(Exception e){throw new PersistenceException(e);}
    }

    @Override public synchronized void recordPolicyEvaluation(PolicyAuditDraft draft) {
        if (draft == null || draft.threadId() == null || draft.turnId() == null || draft.callId() == null
                || draft.capabilityClass() == null || draft.decision() == null || draft.createdAt() == null) {
            throw new DomainException("INVALID_PERMISSION_AUDIT");
        }
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO permission_audit(id,thread_id,turn_id,call_id,action,capability_class,
                      subject_id,subject_fingerprint,resource_scope_hash,decision,error_code,created_at)
                    VALUES(?,?,?,?, 'POLICY_EVALUATION', ?,?,?,?,?,?,?)
                    """)) {
                int i = 1;
                statement.setString(i++, Ids.newId("aud"));
                statement.setString(i++, draft.threadId());
                statement.setString(i++, draft.turnId());
                statement.setString(i++, draft.callId());
                statement.setString(i++, draft.capabilityClass());
                statement.setString(i++, draft.subjectId());
                statement.setString(i++, draft.subjectFingerprint());
                statement.setString(i++, draft.resourceScopeHash());
                statement.setString(i++, draft.decision());
                statement.setString(i++, draft.errorCode());
                statement.setString(i, draft.createdAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override public List<FileRootSummary> listRoots(String workspaceId) {
        try(Connection c=database.open();PreparedStatement s=c.prepareStatement("SELECT * FROM file_root WHERE workspace_id=? ORDER BY is_default DESC,label,id")){
            s.setString(1,workspaceId);try(ResultSet r=s.executeQuery()){List<FileRootSummary> result=new ArrayList<>();while(r.next())result.add(summary(root(r)));return List.copyOf(result);}
        }catch(SQLException e){throw new PersistenceException(e);}
    }

    @Override public synchronized FileRootSummary addRoot(FileRootDraft draft) {
        validateMode(draft.mode());
        return transaction(c->{
            String now=draft.createdAt().toString();
            try(PreparedStatement s=c.prepareStatement("INSERT INTO file_root(id,workspace_id,label,canonical_path,canonical_path_hash,root_identity,mode,enabled,is_default,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,1,?,?)")){
                s.setString(1,draft.id());s.setString(2,draft.workspaceId());s.setString(3,draft.label());s.setString(4,draft.canonicalPath());s.setString(5,draft.canonicalPathHash());s.setString(6,draft.rootIdentity());s.setString(7,draft.mode());s.setInt(8,draft.enabled()?1:0);s.setInt(9,draft.defaultRoot()?1:0);s.setString(10,now);s.setString(11,now);s.executeUpdate();
            }catch(SQLException e){if(e.getMessage()!=null&&e.getMessage().contains("UNIQUE"))throw new DomainException("FILE_ROOT_CONFLICT");throw e;}
            return summary(requireRoot(c,draft.id()));
        });
    }

    @Override public synchronized FileRootSummary updateRoot(String rootId,String mode,boolean enabled,long expectedVersion){
        validateMode(mode);return transaction(c->{FileRootRecord root=requireRoot(c,rootId);if(root.version()!=expectedVersion)throw new DomainException("FILE_ROOT_STALE");String now=Instant.now().toString();
            try(PreparedStatement s=c.prepareStatement("UPDATE file_root SET mode=?,enabled=?,version=version+1,updated_at=? WHERE id=? AND version=?")){s.setString(1,mode);s.setInt(2,enabled?1:0);s.setString(3,now);s.setString(4,rootId);s.setLong(5,expectedVersion);if(s.executeUpdate()!=1)throw new DomainException("FILE_ROOT_STALE");}
            rootAudit(c,root,"ROOT_UPDATE",now);return summary(requireRoot(c,rootId));});
    }

    @Override public synchronized void removeRoot(String rootId,long expectedVersion){transaction(c->{FileRootRecord root=requireRoot(c,rootId);if(root.defaultRoot())throw new DomainException("FILE_ROOT_DEFAULT");if(root.version()!=expectedVersion)throw new DomainException("FILE_ROOT_STALE");rootAudit(c,root,"ROOT_REMOVE",Instant.now().toString());execute(c,"DELETE FROM file_root WHERE id=? AND version=?",rootId,expectedVersion);return null;});}

    @Override public FileRootRecord getRoot(String rootId){try(Connection c=database.open()){return requireRoot(c,rootId);}catch(SQLException e){throw new PersistenceException(e);}}

    public synchronized FileRootSummary ensureDefaultRoot(FileRootDraft draft){
        List<FileRootSummary> roots=listRoots(draft.workspaceId());
        FileRootSummary existing=roots.stream().filter(FileRootSummary::defaultRoot).findFirst().orElse(null);
        if(existing==null)return addRoot(draft);
        if(existing.pathHash().equals(draft.canonicalPathHash()))return existing;
        return transaction(c->{FileRootRecord current=requireRoot(c,existing.id());String now=draft.createdAt().toString();rootAudit(c,current,"ROOT_REPLACE",now);
            try(PreparedStatement s=c.prepareStatement("UPDATE file_root SET label=?,canonical_path=?,canonical_path_hash=?,root_identity=?,mode=?,enabled=1,version=version+1,updated_at=? WHERE id=?")){
                s.setString(1,draft.label());s.setString(2,draft.canonicalPath());s.setString(3,draft.canonicalPathHash());s.setString(4,draft.rootIdentity());s.setString(5,draft.mode());s.setString(6,now);s.setString(7,existing.id());s.executeUpdate();
            }catch(SQLException exception){if(exception.getMessage()!=null&&exception.getMessage().contains("UNIQUE"))throw new DomainException("FILE_ROOT_CONFLICT");throw exception;}
            return summary(requireRoot(c,existing.id()));});
    }

    private ApprovalResolution terminalApproval(Connection c,ApprovalSummary approval,String decision,String code,Instant now,boolean resumeTurn)throws Exception{
        execute(c,"UPDATE approval SET decision=?,resolution_code=?,resolved_at=? WHERE id=? AND decision='PENDING'",decision,code,now.toString(),approval.id());
        if(resumeTurn)execute(c,"UPDATE turn SET status='RUNNING' WHERE id=? AND status='WAITING_APPROVAL'",approval.turnId());
        long sequence=lastSequence(c,approval.turnId())+1;ApprovalSummary resolved=findApproval(c,approval.id());Map<String,Object> value=resolutionValue(resolved);
        insertItem(c,Ids.newId("itm"),approval.threadId(),approval.turnId(),sequence,"APPROVAL","USER",Map.of("type","CUSTOM approval.resolved","value",value),now);
        audit(c,toDraft(approval),decision,decision,code,now);execute(c,"UPDATE thread SET updated_at=? WHERE id=?",now.toString(),approval.threadId());
        return new ApprovalResolution(resolved,sequence,false);
    }

    private static Map<String,Object> resolutionValue(ApprovalSummary a){return Map.of("schemaVersion",1,"approvalId",a.id(),"toolCallId",a.callId(),"decision",a.decision(),"requestFingerprint",a.requestFingerprint());}
    private void audit(Connection c,ApprovalDraft d,String action,String decision,String error,Instant at)throws SQLException{try(PreparedStatement s=c.prepareStatement("INSERT INTO permission_audit(id,thread_id,turn_id,approval_id,call_id,action,capability_class,subject_id,subject_fingerprint,resource_scope_hash,decision,error_code,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")){int i=1;s.setString(i++,Ids.newId("aud"));s.setString(i++,d.threadId());s.setString(i++,d.turnId());s.setString(i++,d.id());s.setString(i++,d.callId());s.setString(i++,action);s.setString(i++,d.capabilityClass());s.setString(i++,d.toolId());s.setString(i++,d.subjectFingerprint());s.setString(i++,d.resourceScopeHash());s.setString(i++,decision);s.setString(i++,error);s.setString(i,d.createdAt().toString());s.executeUpdate();}}
    private void rootAudit(Connection c,FileRootRecord r,String action,String at)throws SQLException{try(PreparedStatement s=c.prepareStatement("INSERT INTO permission_audit(id,action,capability_class,subject_id,resource_scope_hash,decision,created_at) VALUES(?,?,?,?,?,?,?)")){s.setString(1,Ids.newId("aud"));s.setString(2,action);s.setString(3,"WRITE");s.setString(4,r.id());s.setString(5,r.pathHash());s.setString(6,action);s.setString(7,at);s.executeUpdate();}}
    private ApprovalDraft toDraft(ApprovalSummary a){return new ApprovalDraft(a.id(),a.threadId(),a.turnId(),a.callId(),a.toolId(),a.capabilityClass(),a.riskLevel(),a.requestFingerprint(),"","","",a.safeRequest(),a.expiresAt(),a.resolvedAt()==null?a.createdAt():a.resolvedAt());}
    private ApprovalSummary requireApproval(Connection c,String id)throws Exception{ApprovalSummary a=findApproval(c,id);if(a==null)throw new DomainException("APPROVAL_NOT_FOUND");return a;}
    private ApprovalSummary findApproval(Connection c,String id)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT * FROM approval WHERE id=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?approval(r):null;}}}
    private ApprovalSummary findApprovalByCall(Connection c,String call)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT * FROM approval WHERE call_id=?")){s.setString(1,call);try(ResultSet r=s.executeQuery()){return r.next()?approval(r):null;}}}
    private ApprovalSummary findPendingForTurn(Connection c,String turn)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT * FROM approval WHERE turn_id=? AND decision='PENDING'")){s.setString(1,turn);try(ResultSet r=s.executeQuery()){return r.next()?approval(r):null;}}}
    private ApprovalSummary approval(ResultSet r)throws Exception{return new ApprovalSummary(r.getString("id"),r.getString("thread_id"),r.getString("turn_id"),r.getString("call_id"),r.getString("tool_id"),r.getString("capability_class"),r.getString("risk_level"),r.getString("request_fingerprint"),mapper.readValue(r.getString("request_json"),new TypeReference<>(){}),r.getString("decision"),Instant.parse(r.getString("expires_at")),instant(r.getString("created_at")),instant(r.getString("resolved_at")),r.getString("resolution_code"));}
    private static Instant instant(String value){return value==null?null:Instant.parse(value);}
    private static void requireDraft(ApprovalDraft d){if(d==null||d.id()==null||d.threadId()==null||d.turnId()==null||d.callId()==null||d.requestFingerprint()==null||d.safeRequest()==null)throw new DomainException("INVALID_APPROVAL");}
    private static void validateMode(String mode){if(!List.of("READ_ONLY","READ_WRITE").contains(mode))throw new DomainException("FILE_ROOT_MODE_INVALID");}
    private FileRootRecord requireRoot(Connection c,String id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT * FROM file_root WHERE id=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new DomainException("FILE_ROOT_NOT_FOUND");return root(r);}}}
    private static FileRootRecord root(ResultSet r)throws SQLException{return new FileRootRecord(r.getString("id"),r.getString("workspace_id"),r.getString("label"),r.getString("canonical_path"),r.getString("canonical_path_hash"),r.getString("root_identity"),r.getString("mode"),r.getInt("enabled")!=0,r.getInt("is_default")!=0,r.getLong("version"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));}
    private static FileRootSummary summary(FileRootRecord r){return new FileRootSummary(r.id(),r.workspaceId(),r.label(),masked(r.canonicalPath()),r.pathHash(),r.mode(),r.enabled(),r.defaultRoot(),r.version(),r.createdAt(),r.updatedAt());}
    private static String masked(String path){String normalized=path.replace('\\','/');int slash=normalized.lastIndexOf('/');return slash<0?"…": "…/"+normalized.substring(slash+1);}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new PersistenceException(e);}}
    private static String turnStatus(Connection c,String id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT status FROM turn WHERE id=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):null;}}}
    private static long lastSequence(Connection c,String turn)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM item WHERE turn_id=?")){s.setString(1,turn);try(ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}}
    private static long requiredSequence(Connection c,String approval)throws SQLException{return eventSequence(c,approval,"CUSTOM approval.required");}
    private static Long resolvedSequence(Connection c,String approval)throws SQLException{long value=eventSequence(c,approval,"CUSTOM approval.resolved");return value<0?null:value;}
    private static long eventSequence(Connection c,String approval,String type)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT sequence FROM item WHERE type='APPROVAL' AND json_extract(payload_json,'$.type')=? AND json_extract(payload_json,'$.value.approvalId')=? ORDER BY sequence DESC LIMIT 1")){s.setString(1,type);s.setString(2,approval);try(ResultSet r=s.executeQuery()){return r.next()?r.getLong(1):-1;}}}
    private void insertItem(Connection c,String id,String thread,String turn,long sequence,String type,String visibility,Object payload,Instant at)throws Exception{try(PreparedStatement s=c.prepareStatement("INSERT INTO item(id,thread_id,turn_id,sequence,type,visibility,payload_json,created_at) VALUES(?,?,?,?,?,?,?,?)")){s.setString(1,id);s.setString(2,thread);s.setString(3,turn);s.setLong(4,sequence);s.setString(5,type);s.setString(6,visibility);s.setString(7,json(payload));s.setString(8,at.toString());s.executeUpdate();}}
    private static void execute(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}}
    private <T>T transaction(SqlWork<T> work){try(Connection c=database.open()){c.setAutoCommit(false);try{T v=work.run(c);c.commit();return v;}catch(Exception e){c.rollback();if(e instanceof RuntimeException r)throw r;throw new PersistenceException(e);}}catch(SQLException e){throw new PersistenceException(e);}}
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws Exception;}
    public static final class DomainException extends RuntimeException{public DomainException(String code){super(code);}public String code(){return getMessage();}}
    public static final class PersistenceException extends RuntimeException{public PersistenceException(Throwable cause){super("Permission persistence failed",cause);}}
}
