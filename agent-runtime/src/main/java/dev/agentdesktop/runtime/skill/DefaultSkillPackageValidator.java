package dev.agentdesktop.runtime.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class DefaultSkillPackageValidator implements SkillPackageValidator {
    public static final int MAX_SKILL_BYTES = 128 * 1024;
    public static final int MAX_FRONTMATTER_BYTES = 64 * 1024;
    public static final int MAX_RESOURCE_BYTES = 256 * 1024;
    public static final int MAX_RESOURCES = 256;
    // Real-world Skills may vendor structured schemas below scripts/ (for example
    // scripts/office/schemas/ecma/fourth-edition/*.xsd). Keep traversal bounded,
    // while allowing those resources to remain in their canonical package layout.
    public static final int MAX_DEPTH = 8;
    public static final long MAX_PACKAGE_BYTES = 20L * 1024 * 1024;

    private static final Pattern NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret|password|authorization)\\s*[:=]\\s*(?:bearer\\s+)?[A-Za-z0-9_./+=-]{12,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

    private final ObjectMapper mapper;

    public DefaultSkillPackageValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public ValidatedSkillPackage validate(Path isolatedSkillRoot) {
        Path root = Objects.requireNonNull(isolatedSkillRoot).toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
                throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
            }
            Path realRoot = root.toRealPath();
            Path documentPath = realRoot.resolve("SKILL.md");
            if (!Files.isRegularFile(documentPath, NOFOLLOW_LINKS) || Files.isSymbolicLink(documentPath)) {
                throw new SkillException("SKILL_INVALID_FRONTMATTER");
            }
            byte[] documentBytes = boundedRead(documentPath, MAX_SKILL_BYTES, "SKILL_INVALID_FRONTMATTER");
            String document = decodeUtf8(documentBytes, "SKILL_INVALID_FRONTMATTER");
            if (containsSecret(document)) throw new SkillException("SKILL_SECRET_DETECTED");
            Frontmatter parsed = frontmatter(document);
            Map<String, Object> yaml = parseYaml(parsed.yaml());
            String name = string(yaml.remove("name"), 1, 64);
            String description = string(yaml.remove("description"), 1, 1024);
            if (!validSkillName(name) || !name.equals(realRoot.getFileName().toString())) {
                throw new SkillException("SKILL_NAME_INVALID");
            }
            if (parsed.body().isBlank()) throw new SkillException("SKILL_INVALID_FRONTMATTER");
            String license = optionalString(yaml.remove("license"), 500);
            String compatibility = optionalString(yaml.remove("compatibility"), 500);
            String allowedTools = optionalString(yaml.remove("allowed-tools"), 2048);
            Map<String, String> metadata = metadata(yaml.remove("metadata"));
            Map<String, Object> extensions = sanitizeExtensions(yaml);
            List<String> warnings = new ArrayList<>();
            if (document.lines().count() > 500 || document.codePointCount(0, document.length()) / 4 > 5000) {
                warnings.add("SKILL_CONTEXT_LARGE");
            }
            List<ValidatedSkillResource> resources = scanResources(realRoot);
            long totalBytes = documentBytes.length + resources.stream().mapToLong(ValidatedSkillResource::byteSize).sum();
            return new ValidatedSkillPackage(name, description, license, compatibility, metadata,
                    allowedTools, extensions, sha256(documentBytes), packageHash(documentPath, documentBytes, resources),
                    totalBytes, List.copyOf(warnings), resources);
        } catch (SkillException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE", exception);
        } catch (Exception exception) {
            throw new SkillException("SKILL_INVALID_FRONTMATTER", exception);
        }
    }

    public static boolean validSkillName(String name) {
        if (name == null || !NAME.matcher(name).matches()) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        return !upper.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");
    }

    public static byte[] boundedRead(Path path, int max, String code) {
        try {
            long size = Files.size(path);
            if (size > max) throw new SkillException(code);
            try (var input = Files.newInputStream(path); var out = new ByteArrayOutputStream((int) Math.min(size, max))) {
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > max) throw new SkillException(code);
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (SkillException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SkillException(code, exception);
        }
    }

    public static String decodeUtf8(byte[] bytes, String code) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer result = decoder.decode(ByteBuffer.wrap(bytes));
            String text = result.toString();
            if (text.indexOf('\0') >= 0) throw new CharacterCodingException();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException exception) {
            throw new SkillException(code, exception);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static boolean containsSecret(String text) {
        return SECRET_VALUE.matcher(text).find() || PRIVATE_KEY.matcher(text).find();
    }

    private List<ValidatedSkillResource> scanResources(Path skillRoot) throws IOException {
        List<ValidatedSkillResource> resources = new ArrayList<>();
        boolean invalidPath = false;
        try (Stream<Path> stream = Files.walk(skillRoot, MAX_DEPTH + 2)) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(skillRoot) || path.equals(skillRoot.resolve("SKILL.md"))) continue;
                Path relative = skillRoot.relativize(path);
                if (relative.getNameCount() > MAX_DEPTH || Files.isSymbolicLink(path)) {
                    invalidPath = true;
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attributes.isDirectory()) continue;
                if (!attributes.isRegularFile()) {
                    invalidPath = true;
                    continue;
                }
                Path real = path.toRealPath();
                if (!real.startsWith(skillRoot)) {
                    invalidPath = true;
                    continue;
                }
                if (resources.size() >= MAX_RESOURCES) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
                String relativePath = relative.toString().replace('\\', '/');
                String hash = hashFile(real);
                if (attributes.size() <= MAX_RESOURCE_BYTES) {
                    byte[] content = boundedRead(real, MAX_RESOURCE_BYTES, "SKILL_RESOURCE_TOO_LARGE");
                    try {
                        String text = decodeUtf8(content, "SKILL_RESOURCE_BINARY");
                        if (containsSecret(text)) throw new SkillException("SKILL_SECRET_DETECTED");
                    } catch (SkillException exception) {
                        if (!"SKILL_RESOURCE_BINARY".equals(exception.code())) throw exception;
                    }
                }
                String kind = kind(relativePath);
                resources.add(new ValidatedSkillResource(relativePath, kind, Files.probeContentType(real),
                        attributes.size(), hash, "scripts".equals(kind) || executableExtension(relativePath)));
            }
        }
        if (invalidPath) throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE");
        return List.copyOf(resources);
    }

    private String packageHash(Path documentPath, byte[] documentBytes, List<ValidatedSkillResource> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updatePackageDigest(digest, "SKILL.md", documentBytes.length, sha256(documentBytes));
            for (ValidatedSkillResource resource : resources.stream()
                    .sorted(Comparator.comparing(ValidatedSkillResource::relativePath)).toList()) {
                updatePackageDigest(digest, resource.relativePath(), resource.byteSize(), resource.contentHash());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void updatePackageDigest(MessageDigest digest, String path, long size, String hash) {
        digest.update(path.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(hash.getBytes(StandardCharsets.US_ASCII));
    }

    private Map<String, Object> parseYaml(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_FRONTMATTER_BYTES) {
            throw new SkillException("SKILL_INVALID_FRONTMATTER");
        }
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(0);
        options.setAllowRecursiveKeys(false);
        options.setCodePointLimit(MAX_FRONTMATTER_BYTES);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(value);
        } catch (Exception exception) {
            throw new SkillException("SKILL_INVALID_FRONTMATTER", exception);
        }
        if (!(loaded instanceof Map<?, ?> map)) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new SkillException("SKILL_INVALID_FRONTMATTER");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private Map<String, String> metadata(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map) || map.size() > 64) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)
                    || key.length() > 100 || value.length() > 1000 || secretName(key) || containsSecret(value)) {
                throw new SkillException("SKILL_SECRET_DETECTED");
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> sanitizeExtensions(Map<String, Object> raw) {
        if (raw.size() > 64) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) result.put(entry.getKey(), sanitize(entry.getValue(), 0));
        try {
            if (mapper.writeValueAsBytes(result).length > 16 * 1024) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        } catch (SkillException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SkillException("SKILL_INVALID_FRONTMATTER", exception);
        }
        return Map.copyOf(result);
    }

    private Object sanitize(Object value, int depth) {
        if (depth > 5) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof List<?> list) {
            if (list.size() > 64) throw new SkillException("SKILL_INVALID_FRONTMATTER");
            return list.stream().map(item -> sanitize(item, depth + 1)).toList();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 64) throw new SkillException("SKILL_INVALID_FRONTMATTER");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new SkillException("SKILL_INVALID_FRONTMATTER");
                result.put(key, sanitize(entry.getValue(), depth + 1));
            }
            return result;
        }
        throw new SkillException("SKILL_INVALID_FRONTMATTER");
    }

    private static Frontmatter frontmatter(String document) {
        if (!document.startsWith("---\n") && !document.startsWith("---\r\n")) {
            throw new SkillException("SKILL_INVALID_FRONTMATTER");
        }
        int bodyStart = document.indexOf('\n') + 1;
        int close = -1;
        for (int cursor = bodyStart; cursor < document.length();) {
            int end = document.indexOf('\n', cursor);
            if (end < 0) end = document.length();
            String line = document.substring(cursor, end).replace("\r", "");
            if ("---".equals(line)) {
                close = cursor;
                bodyStart = end < document.length() ? end + 1 : end;
                break;
            }
            cursor = end + 1;
        }
        if (close < 0) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        return new Frontmatter(document.substring(document.indexOf('\n') + 1, close), document.substring(bodyStart));
    }

    private static String string(Object value, int min, int max) {
        if (!(value instanceof String text)) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        int size = text.codePointCount(0, text.length());
        if (size < min || size > max || text.isBlank()) throw new SkillException("SKILL_INVALID_FRONTMATTER");
        return text;
    }

    private static String optionalString(Object value, int max) {
        return value == null ? null : string(value, 1, max);
    }

    private static String hashFile(Path path) {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new SkillException("SKILL_RESOURCE_OUT_OF_SCOPE", exception);
        }
    }

    private static String kind(String path) {
        int slash = path.indexOf('/');
        String first = slash < 0 ? "" : path.substring(0, slash);
        return switch (first) {
            case "scripts" -> "scripts";
            case "references" -> "references";
            case "assets" -> "assets";
            default -> "other";
        };
    }

    private static boolean executableExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sh") || lower.endsWith(".ps1") || lower.endsWith(".bat")
                || lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".js")
                || lower.endsWith(".py") || lower.endsWith(".jar");
    }

    private static boolean secretName(String key) {
        String value = key.toLowerCase(Locale.ROOT);
        return value.contains("secret") || value.contains("password") || value.contains("token")
                || value.contains("api_key") || value.contains("apikey") || value.contains("authorization");
    }

    private record Frontmatter(String yaml, String body) {}
}
