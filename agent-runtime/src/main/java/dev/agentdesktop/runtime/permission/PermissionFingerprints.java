package dev.agentdesktop.runtime.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentdesktop.runtime.permission.PermissionModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PermissionFingerprints {
    public static final int POLICY_VERSION=1;
    private PermissionFingerprints(){}
    public static String argumentsHash(ObjectMapper mapper,JsonNode arguments){return sha256(canonical(mapper,arguments));}
    public static String invocation(String threadId,String callId,PermissionSubject subject,CapabilityClass capability,
                                    ResourceScope resource,String argumentsHash){
        return sha256(join(String.valueOf(POLICY_VERSION),threadId,callId,subject.kind(),subject.subjectId(),
                subject.sourceFingerprint(),capability.name(),resourceText(resource),argumentsHash));
    }
    public static String threadGrant(String threadId,PermissionSubject subject,CapabilityClass capability,ResourceScope resource){
        return sha256(join(String.valueOf(POLICY_VERSION),threadId,subject.kind(),subject.subjectId(),
                subject.sourceFingerprint(),capability.name(),resource.scopeClass()));
    }
    public static String sha256(String value){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return "sha256:"+java.util.HexFormat.of().formatHex(bytes);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String canonical(ObjectMapper mapper,JsonNode value){try{return mapper.writeValueAsString(sort(value));}catch(Exception e){throw new PermissionException("TOOL_ARGUMENTS_INVALID");}}
    private static JsonNode sort(JsonNode value){
        if(value==null||value.isNull())return com.fasterxml.jackson.databind.node.NullNode.instance;
        if(value.isObject()){ObjectNode result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();List<String> names=new ArrayList<>();value.fieldNames().forEachRemaining(names::add);names.sort(Comparator.naturalOrder());for(String name:names)result.set(name,sort(value.get(name)));return result;}
        if(value.isArray()){ArrayNode result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();value.forEach(item->result.add(sort(item)));return result;}
        return value;
    }
    private static String resourceText(ResourceScope r){return join(r.rootId(),r.relativeTarget(),r.endpointOrigin(),r.skillId(),r.resourceId(),r.contentHash(),r.scopeClass());}
    private static String join(String... values){return String.join("\0",java.util.Arrays.stream(values).map(value->value==null?"":value).toList());}
}
