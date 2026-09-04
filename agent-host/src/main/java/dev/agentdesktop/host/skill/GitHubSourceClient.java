package dev.agentdesktop.host.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.runtime.skill.DefaultSkillPackageValidator;
import dev.agentdesktop.runtime.skill.importing.GitHubSkillFetcher;
import dev.agentdesktop.runtime.skill.importing.SkillImportException;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.FetchedGitHubSkill;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.GitHubSkillSource;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.ImportSourceSummary;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.ImportSourceType;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class GitHubSourceClient implements GitHubSkillFetcher {
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI apiBase;

    public GitHubSourceClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER).build(), URI.create("https://api.github.com"));
    }

    GitHubSourceClient(ObjectMapper mapper, HttpClient client, URI apiBase) {
        this.mapper = Objects.requireNonNull(mapper);
        this.client = Objects.requireNonNull(client);
        this.apiBase = Objects.requireNonNull(apiBase);
    }

    @Override
    public FetchedGitHubSkill fetch(GitHubSkillSource source, Path emptyStagingDirectory) {
        Repository repository = parseRepository(source.repositoryUrl());
        String subdirectory = normalizeSubdirectory(source.subdirectory());
        String requestedRef = normalizeRef(source.ref());
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        String effectiveRef = requestedRef;
        if (effectiveRef == null) {
            JsonNode metadata = getJson("/repos/" + repository.owner() + "/" + repository.name(), deadline);
            effectiveRef = requiredText(metadata, "default_branch", "SKILL_IMPORT_GITHUB_NOT_FOUND");
        }
        JsonNode commit = getJson("/repos/" + repository.owner() + "/" + repository.name()
                + "/commits/" + encodeSegment(effectiveRef), deadline);
        String sha = requiredText(commit, "sha", "SKILL_IMPORT_GITHUB_NOT_FOUND").toLowerCase(Locale.ROOT);
        if (!COMMIT.matcher(sha).matches()) throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");

        String directoryName = subdirectory.isEmpty() ? repository.name() : subdirectory.substring(subdirectory.lastIndexOf('/') + 1);
        if (!DefaultSkillPackageValidator.validSkillName(directoryName)) {
            throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID", "SKILL_NAME_INVALID");
        }
        Path skillRoot = emptyStagingDirectory.resolve(directoryName).normalize();
        if (!skillRoot.getParent().equals(emptyStagingDirectory)) throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
        try { Files.createDirectories(skillRoot); }
        catch (IOException exception) { throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID", exception); }

        ArrayDeque<RemoteDirectory> pending = new ArrayDeque<>();
        pending.add(new RemoteDirectory(subdirectory, "", 0));
        Set<String> caseFolded = new HashSet<>();
        int fileCount = 0;
        long totalBytes = 0;
        while (!pending.isEmpty()) {
            if (Instant.now().isAfter(deadline)) throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");
            RemoteDirectory directory = pending.removeFirst();
            JsonNode listing = getJson(contentsPath(repository, directory.apiPath(), sha), deadline);
            if (!listing.isArray()) throw new SkillImportException("SKILL_IMPORT_GITHUB_NOT_FOUND");
            for (JsonNode entry : listing) {
                String type = requiredText(entry, "type", "SKILL_IMPORT_NETWORK_FAILED");
                String apiPath = requiredText(entry, "path", "SKILL_IMPORT_NETWORK_FAILED");
                String name = requiredText(entry, "name", "SKILL_IMPORT_NETWORK_FAILED");
                validateApiChild(directory.apiPath(), apiPath, name);
                String relative = directory.relativePath().isEmpty() ? name : directory.relativePath() + "/" + name;
                if (!caseFolded.add(relative.toLowerCase(Locale.ROOT))) throw new SkillImportException("SKILL_IMPORT_CASE_COLLISION");
                int depth = relative.split("/", -1).length;
                if (depth > DefaultSkillPackageValidator.MAX_DEPTH) throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
                if ("dir".equals(type)) {
                    pending.addLast(new RemoteDirectory(apiPath, relative, depth));
                    continue;
                }
                if (!"file".equals(type)) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                fileCount++;
                if (fileCount > DefaultSkillPackageValidator.MAX_RESOURCES + 1) throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
                long declaredSize = entry.path("size").asLong(-1);
                int max = "SKILL.md".equals(relative) ? DefaultSkillPackageValidator.MAX_SKILL_BYTES : DefaultSkillPackageValidator.MAX_RESOURCE_BYTES;
                if (declaredSize < 0 || declaredSize > max) throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
                JsonNode file = getJson(contentsPath(repository, apiPath, sha), deadline);
                if (!"file".equals(file.path("type").asText()) || !apiPath.equals(file.path("path").asText())) {
                    throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");
                }
                String encoding = file.path("encoding").asText();
                if (!"base64".equals(encoding)) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                byte[] bytes;
                try { bytes = Base64.getMimeDecoder().decode(file.path("content").asText()); }
                catch (IllegalArgumentException exception) { throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED", exception); }
                if (bytes.length != declaredSize || bytes.length > max) throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");
                totalBytes += bytes.length;
                if (totalBytes > DefaultSkillPackageValidator.MAX_PACKAGE_BYTES) throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
                Path target = skillRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
                if (!target.startsWith(skillRoot)) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                try {
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                } catch (IOException exception) {
                    throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID", exception);
                }
            }
        }
        ImportSourceSummary summary = new ImportSourceSummary(ImportSourceType.GITHUB, repository.canonicalUrl(),
                requestedRef, sha, subdirectory.isEmpty() ? null : subdirectory);
        return new FetchedGitHubSkill(summary, directoryName);
    }

    private JsonNode getJson(String path, Instant deadline) {
        if (Instant.now().isAfter(deadline)) throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");
        URI uri = apiBase.resolve(path);
        if (!uri.getScheme().equals(apiBase.getScheme()) || !uri.getAuthority().equals(apiBase.getAuthority())) {
            throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "agent-desktop-skill-import")
                .GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_RESPONSE_BYTES) throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");
            if (response.statusCode() == 403 || response.statusCode() == 429) {
                throw new SkillImportException("SKILL_IMPORT_GITHUB_RATE_LIMITED");
            }
            if (response.statusCode() == 404) throw new SkillImportException("SKILL_IMPORT_GITHUB_NOT_FOUND");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED");
            }
            return mapper.readTree(response.body());
        } catch (SkillImportException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SkillImportException("SKILL_IMPORT_CANCELLED", exception);
        } catch (Exception exception) {
            throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED", exception);
        }
    }

    private static Repository parseRepository(String value) {
        try {
            URI uri = URI.create(Objects.requireNonNull(value).trim());
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
            }
            String[] segments = uri.getPath().split("/", -1);
            if (segments.length != 3 || segments[1].isBlank() || segments[2].isBlank()) {
                throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
            }
            String owner = segments[1];
            String repo = segments[2].endsWith(".git") ? segments[2].substring(0, segments[2].length() - 4) : segments[2];
            if (!OWNER.matcher(owner).matches() || !REPO.matcher(repo).matches() || repo.equals(".") || repo.equals("..")) {
                throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
            }
            return new Repository(owner, repo, "https://github.com/" + owner + "/" + repo);
        } catch (SkillImportException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID", exception);
        }
    }

    private static String normalizeRef(String value) {
        if (value == null || value.isBlank()) return null;
        String ref = value.trim();
        if (ref.length() > 256 || ref.indexOf('\0') >= 0 || ref.startsWith("-") || ref.contains("..")) {
            throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
        }
        return ref;
    }

    private static String normalizeSubdirectory(String value) {
        if (value == null || value.isBlank()) return "";
        String path = value.trim().replace('\\', '/');
        if (path.length() > 512 || path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
        }
        for (String segment : path.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
            }
        }
        return path;
    }

    private static String contentsPath(Repository repository, String path, String sha) {
        String encodedPath = path.isEmpty() ? "" : "/" + encodePath(path);
        return "/repos/" + repository.owner() + "/" + repository.name() + "/contents" + encodedPath + "?ref=" + sha;
    }

    private static String encodePath(String path) {
        return String.join("/", java.util.Arrays.stream(path.split("/"))
                .map(GitHubSourceClient::encodeSegment).toList());
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void validateApiChild(String parent, String path, String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
        }
        String expected = parent.isEmpty() ? name : parent + "/" + name;
        if (!expected.equals(path)) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
    }

    private static String requiredText(JsonNode node, String field, String code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new SkillImportException(code);
        return value.asText();
    }

    private record Repository(String owner, String name, String canonicalUrl) {}
    private record RemoteDirectory(String apiPath, String relativePath, int depth) {}
}
