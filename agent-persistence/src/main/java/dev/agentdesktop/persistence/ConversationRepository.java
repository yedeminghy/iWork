package dev.agentdesktop.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SQLite implementation.  Every command that changes visible state is one transaction. */
public final class ConversationRepository implements ConversationStore {
    public static final String LOCAL_USER_ID = "usr_local";
    public static final String DEFAULT_WORKSPACE_ID = "ws_default";
    private static final List<String> ACTIVE = List.of("QUEUED", "RUNNING", "WAITING_APPROVAL", "CANCELLING");
    private final LocalDatabase database;
    private final ObjectMapper objectMapper;
    private final TimelineProjector projector;

    public ConversationRepository(LocalDatabase database, ObjectMapper objectMapper) {
        this.database = Objects.requireNonNull(database); this.objectMapper = Objects.requireNonNull(objectMapper);
        this.projector = new TimelineProjector(objectMapper);
    }

    public void initializeProfile(Path workspaceRoot, String displayName) {
        String now = Instant.now().toString();
        inTransaction(connection -> {
            execute(connection, "INSERT INTO local_user(id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name, updated_at=excluded.updated_at",
                    LOCAL_USER_ID, displayName, now, now);
            execute(connection, "INSERT INTO workspace(id, local_user_id, name, root_path, is_default, created_at) VALUES (?, ?, ?, ?, 1, ?) " +
                    "ON CONFLICT(id) DO NOTHING", DEFAULT_WORKSPACE_ID, LOCAL_USER_ID,
                    "Default Workspace", workspaceRoot.toAbsolutePath().normalize().toString(), now);
            return null;
        });
    }

    @Override public synchronized StartTurnResult startTurn(StartTurnCommand command) {
        require(command.threadId(), "threadId"); require(command.turnId(), "turnId"); require(command.runId(), "runId");
        String message = requireMessage(command.message()); String now = command.startedAt().toString();
        return inTransaction(connection -> {
            ThreadRow thread = findThread(connection, command.threadId());
            if (thread == null) {
                String title = titleFromMessage(message);
                execute(connection, "INSERT INTO thread(id, local_user_id, workspace_id, title, model_profile_id, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)", command.threadId(), LOCAL_USER_ID, DEFAULT_WORKSPACE_ID,
                        title, command.modelProfileId(), now, now);
                thread = new ThreadRow(command.threadId(), title, "ACTIVE", command.modelProfileId(), now);
            } else if (!"ACTIVE".equals(thread.status)) throw new DomainException("THREAD_ARCHIVED");
            if (hasActiveTurn(connection, command.threadId())) throw new DomainException("TURN_CONFLICT");
            String messageToPersist=message;
            if(command.retryOfTurnId()!=null){TurnRow original=findTurn(connection,command.retryOfTurnId());if(original==null)throw new DomainException("TURN_NOT_FOUND");if(!original.threadId.equals(command.threadId())||!List.of("FAILED","CANCELLED","INTERRUPTED").contains(original.status))throw new DomainException("TURN_NOT_RETRYABLE");messageToPersist=userMessage(connection,original.id);}
            execute(connection, "INSERT INTO turn(id, thread_id, run_id, status, model_profile_id, iteration_count, started_at,retry_of_turn_id) " +
                    "VALUES (?, ?, ?, 'RUNNING', ?, 0, ?,?)", command.turnId(), command.threadId(), command.runId(), command.modelProfileId(), now,command.retryOfTurnId());
            insertItem(connection, command.userItemId(), command.threadId(), command.turnId(), 0, "USER_MESSAGE", "USER",
                    Map.of("content", messageToPersist,"subtype",command.retryOfTurnId()==null?"MESSAGE":"RETRY"), now);
            execute(connection, "UPDATE thread SET updated_at=? WHERE id=?", now, command.threadId());
            return new StartTurnResult(summary(thread, null, messageToPersist), new TurnSummary(command.turnId(), command.runId(), "RUNNING", now, null, null, 0,command.retryOfTurnId()), 0);
        });
    }

    @Override public synchronized AppendResult appendEvent(AppendEventCommand command) {
        require(command.eventId(), "eventId");
        return inTransaction(connection -> {
            ItemRecord existing = findItem(connection, command.eventId());
            if (existing != null) {
                Map<String, Object> payload = readPayload(existing.payloadJson());
                if (!existing.threadId().equals(command.threadId()) || !existing.turnId().equals(command.turnId()) ||
                        !existing.type().equals(command.type()) || !payload.equals(command.payload())) throw new DomainException("EVENT_CONFLICT");
                return new AppendResult(existing.id(), existing.sequence(), true, payload);
            }
            String status = turnStatus(connection, command.turnId());
            if (status == null) throw new DomainException("TURN_NOT_FOUND");
            if (!"RUNNING".equals(status) && !"QUEUED".equals(status) && !"WAITING_APPROVAL".equals(status)) throw new DomainException("TURN_NOT_ACTIVE");
            long sequence = lastSequence(connection, command.turnId()) + 1;
            insertItem(connection, command.eventId(), command.threadId(), command.turnId(), sequence, command.type(), command.visibility(), command.payload(), command.createdAt().toString());
            execute(connection, "UPDATE thread SET updated_at=? WHERE id=?", command.createdAt().toString(), command.threadId());
            return new AppendResult(command.eventId(), sequence, false, command.payload());
        });
    }

    @Override public synchronized CancelResult requestCancellation(String turnId, Instant requestedAt) {
        return inTransaction(connection -> {
            String status = turnStatus(connection, turnId);
            if (status == null) throw new DomainException("TURN_NOT_FOUND");
            if (!List.of("QUEUED", "RUNNING", "WAITING_APPROVAL").contains(status)) return new CancelResult(turnId, status, false, null);
            String now = requestedAt.toString();
            execute(connection, "UPDATE turn SET status='CANCELLING', cancel_requested_at=? WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL')", now, turnId);
            TurnRow turn = findTurn(connection, turnId);
            long sequence = lastSequence(connection, turnId) + 1;
            insertItem(connection, Ids.newId("itm"), turn.threadId, turnId, sequence, "STATUS", "USER",
                    Map.of("type", "RUN_STATUS", "value", Map.of("status", "cancelling")), now);
            execute(connection, "UPDATE thread SET updated_at=? WHERE id=?", now, turn.threadId);
            return new CancelResult(turnId, "CANCELLING", true, sequence);
        });
    }

    @Override public synchronized FinalizeResult finalizeTurn(FinalizeTurnCommand command) {
        if (!List.of("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED").contains(command.terminalStatus())) throw new DomainException("INVALID_TURN_STATUS");
        return inTransaction(connection -> {
            TurnRow turn = findTurn(connection, command.turnId());
            if (turn == null) throw new DomainException("TURN_NOT_FOUND");
            if (!ACTIVE.contains(turn.status)) return new FinalizeResult(toSummary(connection, turn), null, false);
            String now = command.finishedAt().toString();
            execute(connection, "UPDATE turn SET status=?, finished_at=?, error_code=? WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL','CANCELLING')",
                    command.terminalStatus(), now, command.errorCode(), command.turnId());
            long sequence = lastSequence(connection, command.turnId()) + 1;
            insertItem(connection, command.eventId(), turn.threadId, command.turnId(), sequence, command.itemType(), command.visibility(), command.payload(), now);
            execute(connection, "UPDATE thread SET updated_at=? WHERE id=?", now, turn.threadId);
            return new FinalizeResult(new TurnSummary(turn.id, turn.runId, command.terminalStatus(), turn.startedAt, now, command.errorCode(), sequence, retryOfTurn(connection,turn.id)), sequence, true);
        });
    }

    @Override public synchronized RecoveryReport reconcileUnfinishedTurns(Instant startupAt) {
        return inTransaction(connection -> {
            List<PendingApprovalRow> approvals=new ArrayList<>();
            try(PreparedStatement ps=connection.prepareStatement("SELECT a.id,COALESCE(a.thread_id,t.thread_id),a.turn_id,COALESCE(a.call_id,'legacy_'||a.id),a.tool_id,COALESCE(a.capability_class,a.capability,'UNKNOWN'),COALESCE(a.request_fingerprint,''),a.subject_fingerprint,a.resource_scope_hash FROM approval a JOIN turn t ON t.id=a.turn_id WHERE a.decision='PENDING'");ResultSet rs=ps.executeQuery()){
                while(rs.next())approvals.add(new PendingApprovalRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)));
            }
            for(PendingApprovalRow approval:approvals){
                execute(connection,"UPDATE approval SET decision='INVALIDATED',resolution_code='APPROVAL_INVALIDATED',resolved_at=? WHERE id=? AND decision='PENDING'",startupAt.toString(),approval.id);
                long sequence=lastSequence(connection,approval.turnId)+1;
                insertItem(connection,Ids.newId("itm"),approval.threadId,approval.turnId,sequence,"APPROVAL","USER",Map.of("type","CUSTOM approval.resolved","value",Map.of("schemaVersion",1,"approvalId",approval.id,"toolCallId",approval.callId,"decision","INVALIDATED","requestFingerprint",approval.requestFingerprint)),startupAt.toString());
                execute(connection,"INSERT INTO permission_audit(id,thread_id,turn_id,approval_id,call_id,action,capability_class,subject_id,subject_fingerprint,resource_scope_hash,decision,error_code,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",Ids.newId("aud"),approval.threadId,approval.turnId,approval.id,approval.callId,"INVALIDATE",approval.capabilityClass,approval.toolId,approval.subjectFingerprint,approval.resourceScopeHash,"INVALIDATED","APPROVAL_INVALIDATED",startupAt.toString());
            }
            int interrupted = 0, cancelled = 0;
            try (PreparedStatement ps = connection.prepareStatement("SELECT id, thread_id, run_id, status, started_at FROM turn WHERE status IN ('QUEUED','RUNNING','WAITING_APPROVAL','CANCELLING')"); ResultSet rs = ps.executeQuery()) {
                List<TurnRow> turns = new ArrayList<>(); while (rs.next()) turns.add(new TurnRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), null, null));
                for (TurnRow turn : turns) {
                    boolean wasCancelling = "CANCELLING".equals(turn.status);
                    String terminal = wasCancelling || "WAITING_APPROVAL".equals(turn.status) ? "CANCELLED" : "INTERRUPTED";
                    String code = wasCancelling ? "CANCELLED_DURING_RESTART" : "WAITING_APPROVAL".equals(turn.status) ? "APPROVAL_INVALIDATED" : "HOST_RESTARTED";
                    execute(connection, "UPDATE turn SET status=?, finished_at=?, error_code=? WHERE id=?", terminal, startupAt.toString(), code, turn.id);
                    long sequence = lastSequence(connection, turn.id) + 1;
                    insertItem(connection, Ids.newId("itm"), turn.threadId, turn.id, sequence, "STATUS", "USER",
                            Map.of("type", "RUN_STATUS", "value", Map.of("status", terminal.toLowerCase(), "code", code)), startupAt.toString());
                    execute(connection, "UPDATE thread SET updated_at=? WHERE id=?", startupAt.toString(), turn.threadId);
                    if ("INTERRUPTED".equals(terminal)) interrupted++; else cancelled++;
                }
            }
            return new RecoveryReport(interrupted, cancelled);
        });
    }

    @Override public ThreadPage listThreads(ThreadQuery query) {
        int limit = Math.max(1, Math.min(100, query.limit())); String status = query.status() == null ? "ACTIVE" : query.status();
        Cursor cursor = decodeCursor(query.cursor()); List<ThreadSummary> threads = new ArrayList<>();
        String sql = "SELECT t.id,t.title,t.status,t.model_profile_id,t.updated_at,(SELECT status FROM turn x WHERE x.thread_id=t.id ORDER BY x.started_at DESC,x.id DESC LIMIT 1),(SELECT payload_json FROM item i WHERE i.thread_id=t.id AND i.visibility='USER' AND i.type='USER_MESSAGE' ORDER BY i.created_at DESC,(SELECT started_at FROM turn WHERE id=i.turn_id) DESC,i.sequence DESC,i.id DESC LIMIT 1) FROM thread t WHERE t.status=? " +
                (cursor == null ? "" : "AND (t.updated_at < ? OR (t.updated_at = ? AND t.id < ?)) ") + "ORDER BY t.updated_at DESC,t.id DESC LIMIT ?";
        try (Connection connection = database.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status); int index = 2; if (cursor != null) { ps.setString(index++, cursor.updatedAt); ps.setString(index++, cursor.updatedAt); ps.setString(index++, cursor.id); } ps.setInt(index, limit + 1);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) threads.add(new ThreadSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), preview(rs.getString(7)))); }
        } catch (SQLException exception) { throw new PersistenceException("Cannot list threads", exception); }
        String next = threads.size() > limit ? encodeCursor(threads.removeLast()) : null;
        return new ThreadPage(List.copyOf(threads), next);
    }

    @Override public ThreadSnapshot loadThreadSnapshot(String threadId) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false); ThreadRow thread = findThread(connection, threadId); if (thread == null) throw new DomainException("THREAD_NOT_FOUND");
            List<TurnSummary> turns = listTurns(connection, threadId); List<ItemRecord> items = listVisibleItems(connection, threadId); connection.commit();
            if (items.size() > 20_000) throw new DomainException("TIMELINE_TOO_LARGE");
            return new ThreadSnapshot(2, summary(thread, turns.isEmpty() ? null : turns.getLast().status(), preview(items)), turns, projector.project(items));
        } catch (SQLException exception) { throw new PersistenceException("Cannot load thread snapshot", exception); }
    }

    @Override public List<ConversationMessage> loadConversationHistory(String threadId,String excludedTurnId,int maxMessages,int maxCharacters) {
        require(threadId,"threadId");
        if(maxMessages<1||maxMessages>100||maxCharacters<1_024||maxCharacters>1_000_000)throw new IllegalArgumentException("Invalid conversation history limit");
        List<TimelineEntry> timeline;
        try(Connection connection=database.open()){
            if(findThread(connection,threadId)==null)throw new DomainException("THREAD_NOT_FOUND");
            timeline=projector.project(listVisibleItems(connection,threadId));
        }catch(SQLException exception){throw new PersistenceException("Cannot load conversation history",exception);}
        List<ConversationMessage> candidates=new ArrayList<>();
        for(TimelineEntry entry:timeline){
            if(excludedTurnId!=null&&excludedTurnId.equals(entry.turnId()))continue;
            String role="USER_MESSAGE".equals(entry.kind())?"user":"ASSISTANT_MESSAGE".equals(entry.kind())?"assistant":null;
            if(role==null)continue;
            if("assistant".equals(role)&&!"COMPLETE".equals(String.valueOf(entry.value().get("status"))))continue;
            String content=String.valueOf(entry.value().getOrDefault("content","")).strip();
            if(!content.isEmpty())candidates.add(new ConversationMessage(role,content));
        }
        List<ConversationMessage> reversed=new ArrayList<>();int characters=0;
        for(int i=candidates.size()-1;i>=0&&reversed.size()<maxMessages;i--){
            ConversationMessage candidate=candidates.get(i);int remaining=maxCharacters-characters;if(remaining<=0)break;
            String content=candidate.content();
            if(content.length()>remaining)content=content.substring(content.length()-remaining);
            reversed.add(new ConversationMessage(candidate.role(),content));characters+=content.length();
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    @Override public synchronized ThreadSummary renameThread(String threadId, String title) {
        String valid = validateTitle(title); String now = Instant.now().toString();
        return inTransaction(connection -> { ThreadRow row = requireThread(connection, threadId); execute(connection, "UPDATE thread SET title=?,updated_at=? WHERE id=?", valid, now, threadId); return summary(new ThreadRow(row.id, valid, row.status, row.modelProfileId, now), null, null); });
    }

    @Override public synchronized ThreadSummary setThreadArchived(String threadId, boolean archived) {
        String now = Instant.now().toString();
        return inTransaction(connection -> { ThreadRow row = requireThread(connection, threadId); if (archived && hasActiveTurn(connection, threadId)) throw new DomainException("THREAD_BUSY");
            String status = archived ? "ARCHIVED" : "ACTIVE"; execute(connection, "UPDATE thread SET status=?,archived_at=?,updated_at=? WHERE id=?", status, archived ? now : null, now, threadId);
            return summary(new ThreadRow(row.id, row.title, status, row.modelProfileId, now), null, null); });
    }

    @Override public synchronized ThreadSummary selectThreadProfile(String threadId,String modelProfileId){
        require(modelProfileId,"modelProfileId");String now=Instant.now().toString();
        return inTransaction(connection->{ThreadRow row=requireThread(connection,threadId);if(hasActiveTurn(connection,threadId))throw new DomainException("THREAD_BUSY");
            execute(connection,"UPDATE thread SET model_profile_id=?,updated_at=? WHERE id=?",modelProfileId,now,threadId);
            return summary(new ThreadRow(row.id,row.title,row.status,modelProfileId,now),null,null);});
    }

    @Override public synchronized AppendResult appendSteering(String turnId,String itemId,String message,Instant createdAt){
        String valid=requireMessage(message);if(valid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new DomainException("STEERING_TOO_LARGE");
        return inTransaction(connection->{TurnRow turn=findTurn(connection,turnId);if(turn==null)throw new DomainException("TURN_NOT_FOUND");if(!List.of("RUNNING","WAITING_APPROVAL").contains(turn.status))throw new DomainException("TURN_ALREADY_FINISHED");
            try(PreparedStatement count=connection.prepareStatement("SELECT COUNT(*),COALESCE(SUM(length(CAST(json_extract(payload_json,'$.content') AS BLOB))),0) FROM item WHERE turn_id=? AND type='USER_MESSAGE' AND json_extract(payload_json,'$.subtype')='STEERING'")){count.setString(1,turnId);try(ResultSet result=count.executeQuery()){result.next();if(result.getInt(1)>=5||result.getLong(2)+valid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new DomainException("STEERING_LIMIT_EXCEEDED");}}
            long sequence=lastSequence(connection,turnId)+1;Map<String,Object> payload=Map.of("content",valid,"subtype","STEERING");insertItem(connection,itemId,turn.threadId,turnId,sequence,"USER_MESSAGE","USER",payload,createdAt.toString());execute(connection,"UPDATE thread SET updated_at=? WHERE id=?",createdAt.toString(),turn.threadId);return new AppendResult(itemId,sequence,false,payload);});
    }

    @Override public RetryPreparation prepareRetry(String turnId){
        try(Connection connection=database.open()){TurnRow turn=findTurn(connection,turnId);if(turn==null)throw new DomainException("TURN_NOT_FOUND");if(!List.of("FAILED","CANCELLED","INTERRUPTED").contains(turn.status))throw new DomainException("TURN_NOT_RETRYABLE");return new RetryPreparation(turn.id,turn.threadId,userMessage(connection,turn.id),threadProfile(connection,turn.threadId));}catch(SQLException exception){throw new PersistenceException("Cannot prepare retry",exception);}
    }

    // Compatibility methods retained for the Week 1 callers and tests.
    public void ensureThread(String threadId, String modelProfileId, String firstMessage) {
        String now = Instant.now().toString();
        inTransaction(c -> { if (findThread(c, threadId) == null) execute(c,
                "INSERT INTO thread(id,local_user_id,workspace_id,title,model_profile_id,status,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                threadId, LOCAL_USER_ID, DEFAULT_WORKSPACE_ID, titleFromMessage(firstMessage == null ? "New conversation" : firstMessage), modelProfileId, now, now); return null; });
    }
    public void createTurn(String turnId, String threadId, String runId, String modelProfileId) {
        inTransaction(c -> { execute(c, "INSERT INTO turn(id,thread_id,run_id,status,model_profile_id,iteration_count,started_at) VALUES (?,?,?,'RUNNING',?,0,?)",
                turnId, threadId, runId, modelProfileId, Instant.now().toString()); return null; });
    }
    public void appendItem(String itemId, String threadId, String turnId, long sequence, String type, String visibility, Object payload) { inTransaction(c -> { insertItem(c, itemId, threadId, turnId, sequence, type, visibility, payload, Instant.now().toString()); return null; }); }
    public boolean requestCancellation(String turnId) { return requestCancellation(turnId, Instant.now()).cancellationRequested(); }
    public void finishTurn(String turnId, String status, String errorCode) {
        inTransaction(c -> { execute(c, "UPDATE turn SET status=?,finished_at=?,error_code=? WHERE id=?", status, Instant.now().toString(), errorCode, turnId); return null; });
    }

    public List<ItemRecord> listItems(String threadId) {
        try (Connection connection = database.open()) { return listItems(connection, threadId); } catch (SQLException exception) { throw new PersistenceException("Cannot list items", exception); }
    }
    /** Compatibility read endpoint: intentionally excludes SYSTEM_AUDIT records. */
    public List<ItemRecord> listVisibleItems(String threadId) {
        try (Connection connection = database.open()) { return listVisibleItems(connection, threadId); } catch (SQLException exception) { throw new PersistenceException("Cannot list visible items", exception); }
    }
    private List<ItemRecord> listVisibleItems(Connection c, String threadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id,thread_id,turn_id,sequence,type,visibility,payload_json,created_at FROM item WHERE thread_id=? AND visibility='USER' ORDER BY (SELECT started_at FROM turn WHERE id=item.turn_id),sequence,id")) {
            ps.setString(1, threadId); try (ResultSet rs = ps.executeQuery()) { List<ItemRecord> result = new ArrayList<>(); while (rs.next()) result.add(new ItemRecord(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8))); return result; }
        }
    }
    private List<ItemRecord> listItems(Connection c, String threadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id,thread_id,turn_id,sequence,type,visibility,payload_json,created_at FROM item WHERE thread_id=? ORDER BY (SELECT started_at FROM turn WHERE id=item.turn_id),sequence,id")) {
            ps.setString(1, threadId); try (ResultSet rs = ps.executeQuery()) { List<ItemRecord> result = new ArrayList<>(); while (rs.next()) result.add(new ItemRecord(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8))); return result; }
        }
    }

    private List<TurnSummary> listTurns(Connection c, String threadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id,run_id,status,started_at,finished_at,error_code,retry_of_turn_id FROM turn WHERE thread_id=? ORDER BY started_at,id")) { ps.setString(1, threadId); try (ResultSet rs = ps.executeQuery()) { List<TurnSummary> result = new ArrayList<>(); while (rs.next()) { String id=rs.getString(1); result.add(new TurnSummary(id,rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),lastSequence(c,id),rs.getString(7))); } return result; } }
    }
    private ThreadRow findThread(Connection c, String id) throws SQLException { try (PreparedStatement ps=c.prepareStatement("SELECT id,title,status,model_profile_id,updated_at FROM thread WHERE id=?")) { ps.setString(1,id); try(ResultSet rs=ps.executeQuery()){ return rs.next()?new ThreadRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)):null; } } }
    private ThreadRow requireThread(Connection c, String id) throws SQLException { ThreadRow row=findThread(c,id); if(row==null) throw new DomainException("THREAD_NOT_FOUND"); return row; }
    private TurnRow findTurn(Connection c, String id) throws SQLException { try(PreparedStatement ps=c.prepareStatement("SELECT id,thread_id,run_id,status,started_at,finished_at,error_code FROM turn WHERE id=?")){ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?new TurnRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)):null;}}}
    private TurnSummary toSummary(Connection c, TurnRow turn) throws SQLException { return new TurnSummary(turn.id,turn.runId,turn.status,turn.startedAt,turn.finishedAt,turn.errorCode,lastSequence(c,turn.id),retryOfTurn(c,turn.id)); }
    private ItemRecord findItem(Connection c,String id) throws SQLException { try(PreparedStatement ps=c.prepareStatement("SELECT id,thread_id,turn_id,sequence,type,visibility,payload_json,created_at FROM item WHERE id=?")){ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?new ItemRecord(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)):null;}}}
    private String turnStatus(Connection c,String id) throws SQLException { TurnRow turn=findTurn(c,id); return turn==null?null:turn.status; }
    private boolean hasActiveTurn(Connection c,String threadId) throws SQLException { try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM turn WHERE thread_id=? AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL','CANCELLING') LIMIT 1")){ps.setString(1,threadId);try(ResultSet rs=ps.executeQuery()){return rs.next();}} }
    private long lastSequence(Connection c,String turnId) throws SQLException { try(PreparedStatement ps=c.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM item WHERE turn_id=?")){ps.setString(1,turnId);try(ResultSet rs=ps.executeQuery()){rs.next();return rs.getLong(1);}} }
    private String userMessage(Connection c,String turnId)throws SQLException{try(PreparedStatement ps=c.prepareStatement("SELECT payload_json FROM item WHERE turn_id=? AND sequence=0 AND type='USER_MESSAGE'")){ps.setString(1,turnId);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new DomainException("TURN_NOT_RETRYABLE");String value=readPayload(rs.getString(1)).get("content") instanceof String text?text:null;if(value==null)throw new DomainException("TURN_NOT_RETRYABLE");return value;}}}
    private String threadProfile(Connection c,String threadId)throws SQLException{return requireThread(c,threadId).modelProfileId;}
    private String retryOfTurn(Connection c,String turnId)throws SQLException{try(PreparedStatement ps=c.prepareStatement("SELECT retry_of_turn_id FROM turn WHERE id=?")){ps.setString(1,turnId);try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}}}
    private void insertItem(Connection c,String id,String threadId,String turnId,long seq,String type,String visibility,Object payload,String created) throws SQLException { try(PreparedStatement ps=c.prepareStatement("INSERT INTO item(id,thread_id,turn_id,sequence,type,visibility,payload_json,created_at) VALUES (?,?,?,?,?,?,?,?)")){ps.setString(1,id);ps.setString(2,threadId);ps.setString(3,turnId);ps.setLong(4,seq);ps.setString(5,type);ps.setString(6,visibility);ps.setString(7,objectMapper.writeValueAsString(payload));ps.setString(8,created);ps.executeUpdate();} catch(JsonProcessingException e){throw new PersistenceException("Cannot serialize item",e);} }
    private void execute(Connection c,String sql,Object... values) throws SQLException { try(PreparedStatement ps=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);ps.executeUpdate();} }
    private <T> T inTransaction(SqlWork<T> work) { try(Connection c=database.open()){c.setAutoCommit(false);try{T result=work.apply(c);c.commit();return result;}catch(Exception e){c.rollback();if(e instanceof RuntimeException r)throw r;throw new PersistenceException("Conversation transaction failed",e);}}catch(SQLException e){throw new PersistenceException("Conversation transaction failed",e);} }
    private Map<String,Object> readPayload(String json) { try{return objectMapper.readValue(json,new TypeReference<>(){});}catch(Exception e){throw new DomainException("EVENT_CONFLICT");} }
    private static ThreadSummary summary(ThreadRow row,String last,String preview){return new ThreadSummary(row.id,row.title,row.status,row.modelProfileId,row.updatedAt,last,preview);}
    private static String preview(String json){ if(json==null)return null; try { var node=new ObjectMapper().readTree(json); var content=node.path("content").asText(node.path("value").path("content").asText("")).replaceAll("\\s+"," ").strip();if(content.isBlank())return null;return content.codePoints().limit(160).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString(); }catch(Exception e){return null;} }
    private static String preview(List<ItemRecord> items){ for(int i=items.size()-1;i>=0;i--){if("USER_MESSAGE".equals(items.get(i).type())){String value=preview(items.get(i).payloadJson());if(value!=null)return value;}}return null; }
    private String encodeCursor(ThreadSummary item){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(Map.of("updatedAt",item.updatedAt(),"id",item.id())));}catch(Exception e){throw new IllegalStateException(e);}}
    private Cursor decodeCursor(String raw){if(raw==null||raw.isBlank())return null;try{Map<String,String> value=objectMapper.readValue(Base64.getUrlDecoder().decode(raw),new TypeReference<>(){});return new Cursor(value.get("updatedAt"),value.get("id"));}catch(Exception e){throw new DomainException("INVALID_CURSOR");}}
    private static String require(String value,String name){if(value==null||value.isBlank())throw new IllegalArgumentException(name+" is required");return value;}
    private static String requireMessage(String value){if(value==null||value.strip().isEmpty())throw new IllegalArgumentException("message is required");return value.strip();}
    private static String titleFromMessage(String value){return value.codePoints().limit(120).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();}
    private static String validateTitle(String title){String cleaned=title==null?"":title.replaceAll("\\p{Cntrl}","").strip();int n=(int)cleaned.codePoints().count();if(n<1||n>120)throw new DomainException("INVALID_TITLE");return cleaned;}
    public record ItemRecord(String id,String threadId,String turnId,long sequence,String type,String visibility,String payloadJson,String createdAt) {}
    private record ThreadRow(String id,String title,String status,String modelProfileId,String updatedAt) {}
    private record TurnRow(String id,String threadId,String runId,String status,String startedAt,String finishedAt,String errorCode) {}
    private record PendingApprovalRow(String id,String threadId,String turnId,String callId,String toolId,String capabilityClass,String requestFingerprint,String subjectFingerprint,String resourceScopeHash) {}
    private record Cursor(String updatedAt,String id) {}
    @FunctionalInterface private interface SqlWork<T>{T apply(Connection connection)throws Exception;}
    public static final class DomainException extends RuntimeException { public DomainException(String code){super(code);} public String code(){return getMessage();} }
    public static final class PersistenceException extends RuntimeException { public PersistenceException(String message,Throwable cause){super(message,cause);} }
}
