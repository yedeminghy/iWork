package dev.agentdesktop.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class ResultSanitizer {
    private static final int VISIBLE_LIMIT=8*1024;
    private static final int MODEL_LIMIT=256*1024;
    private static final Pattern SECRET=Pattern.compile("(?i)(authorization|api[-_ ]?key|token|cookie)([\\\"'\\s:=]+)(?:bearer\\s+)?([^\\s,\\\"']+)");
    private final ObjectMapper mapper;
    public ResultSanitizer(ObjectMapper mapper){this.mapper=mapper;}

    public String summary(Object value){
        String safe=redact(render(value)); byte[] bytes=safe.getBytes(StandardCharsets.UTF_8);
        if(bytes.length<=VISIBLE_LIMIT)return safe;
        return truncateUtf8(safe,VISIBLE_LIMIT)+" … 已截断（"+bytes.length+" 字节）";
    }
    public JsonNode modelResult(Object value){
        JsonNode node=mapper.valueToTree(value); byte[] bytes=node.toString().getBytes(StandardCharsets.UTF_8);
        return bytes.length<=MODEL_LIMIT?node:TextNode.valueOf("Tool result exceeded the 256 KiB model limit");
    }
    public static String redact(String value){return SECRET.matcher(value==null?"":value).replaceAll("$1$2[REDACTED]");}
    private String render(Object value){try{return value instanceof String s?s:mapper.writeValueAsString(value);}catch(Exception e){return "Tool result unavailable";}}
    private static String truncateUtf8(String value,int max){
        int used=0,index=0;while(index<value.length()){int cp=value.codePointAt(index);int n=new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;if(used+n>max)break;used+=n;index+=Character.charCount(cp);}return value.substring(0,index);
    }
}
