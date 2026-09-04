package dev.agentdesktop.runtime.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.runtime.skill.SkillModels.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class LocalSkillRegistry implements SkillRegistry {
    static final int MAX_SKILL_BYTES = DefaultSkillPackageValidator.MAX_SKILL_BYTES;
    static final int MAX_FRONTMATTER_BYTES = DefaultSkillPackageValidator.MAX_FRONTMATTER_BYTES;
    static final int MAX_RESOURCE_BYTES = DefaultSkillPackageValidator.MAX_RESOURCE_BYTES;
    static final int MAX_RESOURCES = DefaultSkillPackageValidator.MAX_RESOURCES;
    static final int MAX_DEPTH = DefaultSkillPackageValidator.MAX_DEPTH;
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret|password|authorization)\\s*[:=]\\s*(?:bearer\\s+)?[A-Za-z0-9_./+=-]{12,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

    private final SkillCatalogStore store;
    private final ObjectMapper mapper;
    private final SkillPackageValidator validator;
    private final Map<SkillScope, Path> roots;
    private final String workspaceId;

    public LocalSkillRegistry(LocalDatabase database, ObjectMapper mapper, Path builtinRoot,
                              Path appHome, Path workspaceRoot, String workspaceId) {
        this.store = new SkillCatalogStore(database, mapper);
        this.mapper = mapper;
        this.validator = new DefaultSkillPackageValidator(mapper);
        this.workspaceId = requireWorkspaceId(workspaceId);
        this.roots = Map.of(
                SkillScope.BUILTIN, normalize(builtinRoot),
                SkillScope.USER, normalize(appHome.resolve("skills")),
                SkillScope.WORKSPACE, normalize(workspaceRoot.resolve(".agent-desktop").resolve("skills")));
    }

    public List<SkillRefreshResult> refreshAll() {
        List<SkillRefreshResult> results = new ArrayList<>();
        for (SkillScope scope : List.of(SkillScope.BUILTIN, SkillScope.USER, SkillScope.WORKSPACE)) {
            try { results.add(refresh(scope, workspaceId)); }
            catch (SkillException exception) { results.add(new SkillRefreshResult(scope, 0, 0, 0, 0, 0, "FAILED", exception.code())); }
        }
        return List.copyOf(results);
    }

    @Override public SkillPage list(SkillQuery query) { return store.list(query, workspaceId); }

    @Override public SkillDetail get(String skillId, boolean includeInstructions) {
        SkillCatalogStore.Entry row = store.require(skillId);
        String instructions = includeInstructions && (row.status()==SkillStatus.VALID||row.status()==SkillStatus.SHADOWED) ? loadInstructionsForView(row) : null;
        return new SkillDetail(row.id(),row.name(),row.description(),row.scope(),row.status(),row.enabled(),row.license(),row.compatibility(),row.metadata(),row.allowedTools(),row.extensions(),row.warnings(),row.errorCode(),row.shadowedById(),row.contentHash(),instructions,store.resources(skillId));
    }

    @Override public List<SkillResourceDescriptor> listResources(String skillId) { return store.resources(skillId); }

    @Override public SkillResourceView readResourceForView(String skillId, String resourceId) {
        SkillCatalogStore.Entry row = store.require(skillId);
        SkillResourceDescriptor resource = store.requireResource(skillId, resourceId);
        ResourceBytes loaded = loadResource(row, resource, false);
        return new SkillResourceView(skillId,resourceId,resource.relativePath(),resource.kind(),resource.mediaType(),resource.byteSize(),resource.contentHash(),loaded.binary(),loaded.text());
    }

    @Override public SkillRefreshResult refresh(SkillScope scope, String requestedWorkspaceId) {
        Objects.requireNonNull(scope, "scope");
        if (scope == SkillScope.WORKSPACE && !workspaceId.equals(requestedWorkspaceId)) throw new SkillException("SKILL_SOURCE_UNAVAILABLE");
        Path root = roots.get(scope);
        List<SkillCatalogStore.DiscoveredSkill> discoveries = scan(scope, root);
        return store.replace(scope, workspaceId, root, discoveries);
    }

    @Override public SkillSummary setEnabled(String skillId, boolean enabled) { return store.setEnabled(skillId, enabled, workspaceId); }
    @Override public SkillCatalogSnapshot snapshot(String requestedWorkspaceId) {
        if (!workspaceId.equals(requestedWorkspaceId)) throw new SkillException("SKILL_SOURCE_UNAVAILABLE");
        SkillPage page = store.list(new SkillQuery(null, SkillStatus.VALID), workspaceId);
        List<SkillMetadata> metadata = page.items().stream().filter(SkillSummary::enabled)
                .map(item -> new SkillMetadata(item.id(),item.name(),item.description(),item.scope())).sorted(Comparator.comparing(SkillMetadata::name)).toList();
        if (metadata.size() > 200 || metadata.stream().mapToInt(item -> item.description().getBytes(StandardCharsets.UTF_8).length).sum() > 64 * 1024)
            throw new SkillException("SKILL_CATALOG_TOO_LARGE");
        return new SkillCatalogSnapshot(workspaceId,page.generation(),metadata);
    }

    SkillCatalogStore.Entry runtimeEntry(String skillId) { return store.require(skillId); }
    void recordActivation(String turnId, SkillCatalogStore.Entry entry, ActivationReason reason) { store.recordActivation(turnId,entry,reason); }
    String loadInstructions(SkillCatalogStore.Entry row) {
        requireActivatable(row);
        return loadInstructionsForView(row);
    }

    private String loadInstructionsForView(SkillCatalogStore.Entry row) {
        Path file = secureFile(row, "SKILL.md");
        byte[] bytes = boundedRead(file, MAX_SKILL_BYTES, "SKILL_INVALID_FRONTMATTER");
        if (!sha256(bytes).equals(row.contentHash())) throw new SkillException("SKILL_STALE");
        String document = decodeUtf8(bytes, "SKILL_INVALID_FRONTMATTER");
        if (containsSecret(document)) throw new SkillException("SKILL_SECRET_DETECTED");
        Frontmatter parsed = frontmatter(document);
        return parsed.body();
    }

    SkillResourceContent readRuntimeResource(SkillCatalogStore.Entry row, SkillResourceDescriptor resource) {
        ResourceBytes loaded = loadResource(row, resource, true);
        if (loaded.binary()) throw new SkillException("SKILL_RESOURCE_BINARY");
        return new SkillResourceContent(row.id(),resource.id(),resource.relativePath(),resource.kind(),resource.mediaType(),resource.byteSize(),resource.contentHash(),loaded.text());
    }

    SkillScriptPlan resolveRuntimeScript(SkillCatalogStore.Entry row,SkillResourceDescriptor resource){
        requireActivatable(row);String lower=resource.relativePath().toLowerCase(Locale.ROOT);String profile=lower.endsWith(".ps1")?"WINDOWS_POWERSHELL_5":lower.endsWith(".py")?"PYTHON_3":lower.endsWith(".js")?"NODE_JS":null;
        if(!"scripts".equals(resource.kind())||!resource.executableCandidate()||profile==null)throw new SkillException("SCRIPT_INTERPRETER_UNAVAILABLE");
        Path script=secureFile(row,resource.relativePath());try{BasicFileAttributes attrs=Files.readAttributes(script,BasicFileAttributes.class,NOFOLLOW_LINKS);if(!attrs.isRegularFile()||Files.isSymbolicLink(script)||attrs.size()!=resource.byteSize()||!hashFile(script).equals(resource.contentHash()))throw new SkillException("SCRIPT_STALE");}
        catch(SkillException e){throw e;}catch(Exception e){throw new SkillException("SCRIPT_STALE",e);}
        return new SkillScriptPlan(row.id(),resource.id(),row.name(),script,resource.contentHash(),profile);
    }

    private List<SkillCatalogStore.DiscoveredSkill> scan(SkillScope scope, Path root) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return List.of();
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) throw new SkillException("SKILL_SOURCE_UNAVAILABLE");
            Path realRoot = root.toRealPath();
            List<Path> children;
            try (Stream<Path> stream = Files.list(root)) {
                children = stream.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
            List<SkillCatalogStore.DiscoveredSkill> result = new ArrayList<>();
            for (Path child : children) result.add(scanSkill(scope, realRoot, child));
            return List.copyOf(result);
        } catch (SkillException exception) { throw exception; }
        catch (IOException exception) { throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception); }
    }

    private SkillCatalogStore.DiscoveredSkill scanSkill(SkillScope scope, Path realRoot, Path directory) {
        String directoryName = directory.getFileName().toString();
        String id = SkillCatalogStore.stableId("skl", scope + "|" + workspaceKey(scope) + "|" + directoryName);
        try {
            if (Files.isSymbolicLink(directory)) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.getParent().equals(realRoot)) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
            SkillPackageValidator.ValidatedSkillPackage validated = validator.validate(realDirectory);
            List<SkillResourceDescriptor> resources = validated.resources().stream()
                    .map(resource -> new SkillResourceDescriptor(
                            SkillCatalogStore.stableId("res", id + "|" + resource.relativePath()),
                            resource.relativePath(), resource.kind(), resource.mediaType(), resource.byteSize(),
                            resource.contentHash(), resource.executableCandidate()))
                    .toList();
            return new SkillCatalogStore.DiscoveredSkill(id, validated.name(), directoryName,
                    validated.description(), validated.license(), validated.compatibility(), validated.metadata(),
                    validated.allowedTools(), validated.extensions(), validated.skillDocumentHash(),
                    validated.warningCodes(), null, resources);
        } catch (SkillException exception) {
            return invalid(id,directoryName,exception.code());
        } catch (Exception exception) {
            return invalid(id,directoryName,"SKILL_INVALID_FRONTMATTER");
        }
    }

    static boolean validSkillName(String name) {
        return DefaultSkillPackageValidator.validSkillName(name);
    }

    private List<SkillResourceDescriptor> scanResources(String skillId, Path skillRoot) throws IOException {
        List<SkillResourceDescriptor> resources = new ArrayList<>(); boolean invalidPath = false;
        try (Stream<Path> stream = Files.walk(skillRoot, MAX_DEPTH + 2)) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(skillRoot) || path.equals(skillRoot.resolve("SKILL.md"))) continue;
                Path relative = skillRoot.relativize(path);
                if (relative.getNameCount() > MAX_DEPTH) { invalidPath = true; continue; }
                if (Files.isSymbolicLink(path)) { invalidPath = true; continue; }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attributes.isDirectory()) continue;
                if (!attributes.isRegularFile()) { invalidPath = true; continue; }
                Path real = path.toRealPath();
                if (!real.startsWith(skillRoot)) { invalidPath = true; continue; }
                if (resources.size() >= MAX_RESOURCES) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
                String relativePath = relative.toString().replace('\\','/');
                String hash = hashFile(real);
                String kind = kind(relativePath);
                String mediaType = Files.probeContentType(real);
                boolean executable = "scripts".equals(kind) || executableExtension(relativePath);
                resources.add(new SkillResourceDescriptor(SkillCatalogStore.stableId("res",skillId+"|"+relativePath),relativePath,kind,mediaType,attributes.size(),hash,executable));
            }
        }
        if (invalidPath) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
        return List.copyOf(resources);
    }

    private ResourceBytes loadResource(SkillCatalogStore.Entry row, SkillResourceDescriptor resource, boolean requireActive) {
        if(requireActive)requireActivatable(row);
        else if(row.status()!=SkillStatus.VALID&&row.status()!=SkillStatus.SHADOWED)throw new SkillException(row.errorCode()==null?"SKILL_NOT_FOUND":row.errorCode());
        Path file = secureFile(row, resource.relativePath());
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(file,BasicFileAttributes.class,NOFOLLOW_LINKS); }
        catch (IOException exception) { throw new SkillException("SKILL_RESOURCE_NOT_FOUND",exception); }
        if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
        if (attributes.size() > MAX_RESOURCE_BYTES) throw new SkillException("SKILL_RESOURCE_TOO_LARGE");
        byte[] bytes = boundedRead(file,MAX_RESOURCE_BYTES,"SKILL_RESOURCE_TOO_LARGE");
        if (attributes.size()!=resource.byteSize() || !sha256(bytes).equals(resource.contentHash())) throw new SkillException("SKILL_STALE");
        try {
            String text = decodeUtf8(bytes,"SKILL_RESOURCE_BINARY");
            if (containsSecret(text)) throw new SkillException("SKILL_SECRET_DETECTED");
            return new ResourceBytes(false,text);
        } catch (SkillException exception) {
            if ("SKILL_RESOURCE_BINARY".equals(exception.code())) return new ResourceBytes(true,null);
            throw exception;
        }
    }

    private Path secureFile(SkillCatalogStore.Entry row, String relative) {
        try {
            Path skillRoot = row.root().resolve(row.directory()).normalize();
            Path realRoot = skillRoot.toRealPath();
            Path candidate = realRoot.resolve(relative).normalize();
            if (!candidate.startsWith(realRoot) || Files.isSymbolicLink(candidate)) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot)) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
            return real;
        } catch (NoSuchFileException exception) { throw new SkillException("SKILL_RESOURCE_NOT_FOUND",exception); }
        catch (IOException exception) { throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE",exception); }
    }

    private void requireActivatable(SkillCatalogStore.Entry row) {
        if (!row.enabled()) throw new SkillException("SKILL_DISABLED");
        if (row.status()==SkillStatus.SHADOWED) throw new SkillException("SKILL_SHADOWED");
        if (row.status()==SkillStatus.MISSING) throw new SkillException("SKILL_NOT_FOUND");
        if (row.status()!=SkillStatus.VALID) throw new SkillException(row.errorCode()==null?"SKILL_INVALID_FRONTMATTER":row.errorCode());
        if (row.scope()==SkillScope.WORKSPACE && !workspaceId.equals(row.workspaceId())) throw new SkillException("SKILL_NOT_FOUND");
    }

    private Map<String,Object> parseYaml(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_FRONTMATTER_BYTES) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        LoaderOptions options = new LoaderOptions(); options.setMaxAliasesForCollections(0); options.setAllowRecursiveKeys(false); options.setCodePointLimit(MAX_FRONTMATTER_BYTES);
        Object loaded;
        try { loaded = new Yaml(new SafeConstructor(options)).load(value); }
        catch (Exception exception) { throw new SkillException("SKILL_INVALID_FRONTMATTER",exception); }
        if (!(loaded instanceof Map<?,?> map)) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        Map<String,Object> result = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry:map.entrySet()) { if (!(entry.getKey() instanceof String key)) throw new SkillException("SKILL_INVALID_FRONTMATTER"); result.put(key,entry.getValue()); }
        return result;
    }

    private Map<String,String> metadata(Object raw) {
        if (raw==null) return Map.of(); if (!(raw instanceof Map<?,?> map) || map.size()>64) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        Map<String,String> result=new LinkedHashMap<>();
        for(var entry:map.entrySet()) { if(!(entry.getKey() instanceof String key)||!(entry.getValue() instanceof String value)||key.length()>100||value.length()>1000||secretName(key)||containsSecret(value)) throw new SkillException("SKILL_SECRET_DETECTED"); result.put(key,value); }
        return Map.copyOf(result);
    }

    private Map<String,Object> sanitizeExtensions(Map<String,Object> raw) {
        if(raw.size()>64)throw new SkillException("SKILL_INVALID_FRONTMATTER");
        Map<String,Object> result=new LinkedHashMap<>(); for(var entry:raw.entrySet())result.put(entry.getKey(),sanitize(entry.getValue(),0));
        try { if(mapper.writeValueAsBytes(result).length>16*1024)throw new SkillException("SKILL_INVALID_FRONTMATTER"); }
        catch(SkillException e){throw e;}catch(Exception e){throw new SkillException("SKILL_INVALID_FRONTMATTER",e);}
        return Map.copyOf(result);
    }
    private Object sanitize(Object value,int depth){if(depth>5)throw new SkillException("SKILL_INVALID_FRONTMATTER");if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;if(value instanceof List<?> list){if(list.size()>64)throw new SkillException("SKILL_INVALID_FRONTMATTER");return list.stream().map(item->sanitize(item,depth+1)).toList();}if(value instanceof Map<?,?> map){if(map.size()>64)throw new SkillException("SKILL_INVALID_FRONTMATTER");Map<String,Object> out=new LinkedHashMap<>();for(var e:map.entrySet()){if(!(e.getKey() instanceof String key))throw new SkillException("SKILL_INVALID_FRONTMATTER");out.put(key,sanitize(e.getValue(),depth+1));}return out;}throw new SkillException("SKILL_INVALID_FRONTMATTER");}

    private static Frontmatter frontmatter(String document) {
        if (!document.startsWith("---\n") && !document.startsWith("---\r\n")) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        int firstEnd=document.indexOf('\n')+1; int close=-1;
        for(int cursor=firstEnd;cursor<document.length();){int end=document.indexOf('\n',cursor);if(end<0)end=document.length();String line=document.substring(cursor,end).replace("\r","");if("---".equals(line)){close=cursor;firstEnd=end<document.length()?end+1:end;break;}cursor=end+1;}
        if(close<0)throw new SkillException("SKILL_INVALID_FRONTMATTER");
        return new Frontmatter(document.substring(document.indexOf('\n')+1,close),document.substring(firstEnd));
    }
    private static String string(Object value,String field,int min,int max){if(!(value instanceof String text))throw new SkillException("SKILL_INVALID_FRONTMATTER");int size=text.codePointCount(0,text.length());if(size<min||size>max||text.isBlank())throw new SkillException("SKILL_INVALID_FRONTMATTER");return text;}
    private static String optionalString(Object value,String field,int max){return value==null?null:string(value,field,1,max);}
    private static byte[] boundedRead(Path path,int max,String code){try{long size=Files.size(path);if(size>max)throw new SkillException(code);try(var input=Files.newInputStream(path);var out=new ByteArrayOutputStream((int)Math.min(size,max))){byte[] buffer=new byte[8192];int read,total=0;while((read=input.read(buffer))>=0){total+=read;if(total>max)throw new SkillException(code);out.write(buffer,0,read);}return out.toByteArray();}}catch(SkillException e){throw e;}catch(IOException e){throw new SkillException(code,e);}}
    private static String decodeUtf8(byte[] bytes,String code){try{CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);CharBuffer result=decoder.decode(ByteBuffer.wrap(bytes));String text=result.toString();if(text.indexOf('\0')>=0)throw new CharacterCodingException();return text.startsWith("\uFEFF")?text.substring(1):text;}catch(CharacterCodingException e){throw new SkillException(code,e);}}
    private static String hashFile(Path path){try(var input=Files.newInputStream(path)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))>=0)digest.update(buffer,0,read);return HexFormat.of().formatHex(digest.digest());}catch(Exception e){throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE",e);}}
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String kind(String path){int slash=path.indexOf('/');String first=slash<0?"":path.substring(0,slash);return switch(first){case "scripts"->"scripts";case "references"->"references";case "assets"->"assets";default->"other";};}
    private static boolean executableExtension(String path){String lower=path.toLowerCase(Locale.ROOT);return lower.endsWith(".sh")||lower.endsWith(".ps1")||lower.endsWith(".bat")||lower.endsWith(".cmd")||lower.endsWith(".exe")||lower.endsWith(".js")||lower.endsWith(".py")||lower.endsWith(".jar");}
    private static boolean containsSecret(String text){return SECRET_VALUE.matcher(text).find()||PRIVATE_KEY.matcher(text).find();}
    private static boolean secretName(String key){String value=key.toLowerCase(Locale.ROOT);return value.contains("secret")||value.contains("password")||value.contains("token")||value.contains("api_key")||value.contains("apikey")||value.contains("authorization");}
    private static String requireWorkspaceId(String value){if(value==null||!value.matches("ws_[A-Za-z0-9_-]{3,128}"))throw new IllegalArgumentException("Invalid workspace id");return value;}
    private static Path normalize(Path value){return Objects.requireNonNull(value).toAbsolutePath().normalize();}
    private String workspaceKey(SkillScope scope){return scope==SkillScope.WORKSPACE?workspaceId:"";}
    private static SkillCatalogStore.DiscoveredSkill invalid(String id,String directory,String code){return new SkillCatalogStore.DiscoveredSkill(id,directory,directory,"",null,null,Map.of(),null,Map.of(),"",List.of(),code,List.of());}
    private record Frontmatter(String yaml,String body){}
    private record ResourceBytes(boolean binary,String text){}
}
