package cn.datadashboard.configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Local immutable configuration revisions. No database, collector, network or legacy-directory access. */
public final class ProjectConfigurationStore {
    public static final String SCHEMA_VERSION = "project-configuration-v2";
    public static final String METRIC_PROFILE = "material-33-v1";
    public static final String ACTOR = "LOCAL_UNAUTHENTICATED";
    public static final Set<String> CATEGORIES = Set.of("LARGE_APPLIANCE", "SMALL_APPLIANCE", "OTHER");
    private static final String POINTER_SCHEMA = "project-configuration-pointer-v1";
    private static final int MAX_DOCUMENT_BYTES = 131_072;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<Path> IN_PROCESS_LOCKS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path localRoot;
    private final Path directory;
    private final java.time.Clock clock;

    public ProjectConfigurationStore(Path workspace, Path storeDirectory) {
        this(workspace, storeDirectory, java.time.Clock.systemUTC());
    }

    public ProjectConfigurationStore(Path workspace, Path storeDirectory, java.time.Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
        try {
            Path root = workspace.toRealPath();
            localRoot = root.resolve(".local").toRealPath();
            require(localRoot.equals(root.resolve(".local")) && Files.isDirectory(localRoot), "CONFIG_UNAVAILABLE");
            directory = storeDirectory.toAbsolutePath().normalize();
            safe(directory);
        } catch (Exception failure) {
            throw sanitized(failure, "CONFIG_UNAVAILABLE");
        }
    }

    public Snapshot snapshot() {
        try { return readSnapshot(); }
        catch (Exception failure) { throw sanitized(failure, "CONFIG_UNAVAILABLE"); }
    }

    /** Explicit initialization only. Existing (even corrupt) current pointers are never overwritten here. */
    public Snapshot initialize(List<Project> projects) {
        List<Project> candidate = validateProjects(projects);
        try {
            createDirectories(directory);
            return locked(() -> {
                Path current = path("current.json");
                require(!Files.exists(current, LinkOption.NOFOLLOW_LINKS), "CONFIG_STALE");
                createDirectories(path("revisions"));
                return publish(candidate, MetricSelection.allMetrics(), null);
            });
        } catch (Exception failure) {
            throw sanitized(failure, "CONFIG_UNAVAILABLE");
        }
    }

    public Preflight preflight(String expectedRevision, List<Project> projects) {
        requireHash(expectedRevision, "CONFIG_INVALID");
        Snapshot current = snapshot();
        require(current.revision.equals(expectedRevision), "CONFIG_STALE");
        return preflight(expectedRevision, projects, current.document.enabledMetrics);
    }

    public Preflight preflight(String expectedRevision, List<Project> projects, List<String> enabledMetrics) {
        requireHash(expectedRevision, "CONFIG_INVALID");
        List<Project> candidate = validateProjects(projects);
        List<String> metrics = validateMetrics(enabledMetrics);
        Snapshot current = snapshot();
        require(current.revision.equals(expectedRevision), "CONFIG_STALE");
        requireStableBindings(current.document.projects, candidate);
        requireChangeWindow(current.document.projects, candidate);
        return new Preflight("READY", expectedRevision, candidateHash(candidate, metrics));
    }

    public Applied apply(String expectedRevision, List<Project> projects, String candidateHash) {
        requireHash(expectedRevision, "CONFIG_INVALID");
        Snapshot current = snapshot();
        require(current.revision.equals(expectedRevision), "CONFIG_STALE");
        return apply(expectedRevision, projects, current.document.enabledMetrics, candidateHash);
    }

    public Applied apply(String expectedRevision, List<Project> projects, List<String> enabledMetrics, String candidateHash) {
        return applyAs(expectedRevision, projects, enabledMetrics, candidateHash, ACTOR);
    }

    public Applied applyAs(String expectedRevision, List<Project> projects, List<String> enabledMetrics, String candidateHash, String actor) {
        require(cn.datadashboard.security.OperationActor.valid(actor), "CONFIG_INVALID");
        requireHash(expectedRevision, "CONFIG_INVALID");
        requireHash(candidateHash, "CONFIG_INVALID");
        List<Project> candidate = validateProjects(projects);
        List<String> metrics = validateMetrics(enabledMetrics);
        require(candidateHash(candidate, metrics).equals(candidateHash), "CONFIG_INVALID");
        try {
            return locked(() -> {
                Snapshot before = readSnapshot();
                require(before.revision.equals(expectedRevision), "CONFIG_STALE");
                requireStableBindings(before.document.projects, candidate);
                requireChangeWindow(before.document.projects, candidate);
                Snapshot after = publish(candidate, metrics, before.revision, actor);
                return new Applied("CURRENT_VERIFIED", after.revision, before.revision, "DISABLED");
            });
        } catch (Exception failure) {
            // CONFIG_UNAVAILABLE can occur after the atomic pointer changes. The caller must reread,
            // never announce rollback/non-publication or automatically retry an unknown outcome.
            throw sanitized(failure, "CONFIG_UNAVAILABLE");
        }
    }

    /** Strict request parser shared with the HTTP boundary; rejects extra fields and wrong scalar types. */
    public static List<Project> parseProjects(JsonNode projects) {
        try {
            require(projects != null && projects.isArray(), "CONFIG_INVALID");
            List<Project> result = new ArrayList<>();
            for (JsonNode project : projects) {
                Set<String> allowed = new HashSet<>(Set.of("id", "name", "advertiserId", "douyinIds", "enabled"));
                if (project.has("publicDouyinId")) allowed.add("publicDouyinId");
                if (project.has("awemeName")) allowed.add("awemeName");
                if (project.has("deleted")) allowed.add("deleted");
                if (project.has("category")) allowed.add("category");
                if (project.has("collectionTheme")) allowed.add("collectionTheme");
                if (project.has("storageAccountId")) allowed.add("storageAccountId");
                keys(project, allowed, "CONFIG_INVALID");
                require(!project.has("deleted") || project.get("deleted").isBoolean(), "CONFIG_INVALID");
                JsonNode anchors = project.get("douyinIds");
                JsonNode enabled = project.get("enabled");
                require(anchors != null && anchors.isArray() && enabled != null && enabled.isBoolean(), "CONFIG_INVALID");
                List<String> ids = new ArrayList<>();
                for (JsonNode anchor : anchors) {
                    require(anchor.isTextual(), "CONFIG_INVALID");
                    ids.add(anchor.stringValue());
                }
                result.add(new Project(text(project, "id", "CONFIG_INVALID"), text(project, "name", "CONFIG_INVALID"),
                        text(project, "advertiserId", "CONFIG_INVALID"), ids, enabled.booleanValue(),
                        optionalText(project, "publicDouyinId"), optionalText(project, "awemeName"), project.path("deleted").asBoolean(false),
                        project.has("category") ? text(project, "category", "CONFIG_INVALID") : "OTHER",
                        project.has("collectionTheme") ? text(project, "collectionTheme", "CONFIG_INVALID") : "QUANYU",
                        project.has("storageAccountId") ? text(project, "storageAccountId", "CONFIG_INVALID") : text(project, "advertiserId", "CONFIG_INVALID")));
            }
            return validateProjects(result);
        } catch (Exception failure) {
            throw sanitized(failure, "CONFIG_INVALID");
        }
    }

    private Snapshot readSnapshot() throws Exception {
        JsonNode pointer = parse(readFile(path("current.json"), 4096));
        keys(pointer, Set.of("schemaVersion", "revision", "sizeBytes"), "CONFIG_UNAVAILABLE");
        require(POINTER_SCHEMA.equals(text(pointer, "schemaVersion", "CONFIG_UNAVAILABLE")), "CONFIG_UNAVAILABLE");
        String revision = text(pointer, "revision", "CONFIG_UNAVAILABLE");
        requireHash(revision, "CONFIG_UNAVAILABLE");
        JsonNode size = pointer.get("sizeBytes");
        require(size != null && size.isIntegralNumber() && size.canConvertToLong()
                && size.longValue() > 0 && size.longValue() <= MAX_DOCUMENT_BYTES, "CONFIG_UNAVAILABLE");
        byte[] bytes = readFile(path("revisions").resolve(revision + ".json"), MAX_DOCUMENT_BYTES);
        require(bytes.length == size.longValue() && sha(bytes).equals(revision), "CONFIG_UNAVAILABLE");
        JsonNode document = parse(bytes);
        String schema = text(document, "schemaVersion", "CONFIG_UNAVAILABLE");
        boolean legacy = "project-configuration-v1".equals(schema);
        keys(document, legacy
                ? Set.of("schemaVersion", "createdAt", "previousRevision", "actor", "metricProfile", "projects")
                : Set.of("schemaVersion", "createdAt", "previousRevision", "actor", "metricProfile", "projects", "enabledMetrics"), "CONFIG_UNAVAILABLE");
        require((legacy || SCHEMA_VERSION.equals(schema))
                && METRIC_PROFILE.equals(text(document, "metricProfile", "CONFIG_UNAVAILABLE"))
                && cn.datadashboard.security.OperationActor.valid(text(document, "actor", "CONFIG_UNAVAILABLE")), "CONFIG_UNAVAILABLE");
        String createdAt = text(document, "createdAt", "CONFIG_UNAVAILABLE");
        require(createdAt.endsWith("Z") && Instant.parse(createdAt) != null, "CONFIG_UNAVAILABLE");
        JsonNode previous = document.get("previousRevision");
        String previousRevision = null;
        if (!previous.isNull()) {
            require(previous.isTextual(), "CONFIG_UNAVAILABLE");
            previousRevision = previous.stringValue();
            requireHash(previousRevision, "CONFIG_UNAVAILABLE");
            require(!previousRevision.equals(revision), "CONFIG_UNAVAILABLE");
        }
        List<Project> projects;
        try { projects = parseProjects(document.get("projects")); }
        catch (Exception failure) { throw new ConfigurationException("CONFIG_UNAVAILABLE"); }
        List<String> metrics;
        try { metrics = legacy ? MetricSelection.allMetrics() : validateMetrics(MetricSelection.strings(document.get("enabledMetrics"))); }
        catch (Exception failure) { throw new ConfigurationException("CONFIG_UNAVAILABLE"); }
        if (!legacy) require(metrics.equals(MetricSelection.strings(document.get("enabledMetrics"))), "CONFIG_UNAVAILABLE");
        return new Snapshot(revision, new Document(schema, createdAt, previousRevision, text(document, "actor", "CONFIG_UNAVAILABLE"), METRIC_PROFILE, projects, metrics));
    }

    private Snapshot publish(List<Project> projects, List<String> metrics, String previousRevision) throws Exception {
        return publish(projects, metrics, previousRevision, ACTOR);
    }
    private Snapshot publish(List<Project> projects, List<String> metrics, String previousRevision, String actor) throws Exception {
        ObjectNode document = projectContent(projects, metrics);
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("createdAt", Instant.now().toString());
        if (previousRevision == null) document.putNull("previousRevision");
        else document.put("previousRevision", previousRevision);
        document.put("actor", actor);
        byte[] bytes = canonicalBytes(document);
        String revision = sha(bytes);
        Path version = path("revisions").resolve(revision + ".json");
        safe(version);
        if (Files.exists(version, LinkOption.NOFOLLOW_LINKS)) {
            require(java.util.Arrays.equals(readFile(version, MAX_DOCUMENT_BYTES), bytes), "CONFIG_UNAVAILABLE");
        } else {
            writeNew(version, bytes);
        }
        // Verify the immutable object separately before publishing a pointer to it.
        byte[] saved = readFile(version, MAX_DOCUMENT_BYTES);
        require(saved.length == bytes.length && sha(saved).equals(revision), "CONFIG_UNAVAILABLE");
        ObjectNode pointer = JSON.createObjectNode().put("schemaVersion", POINTER_SCHEMA)
                .put("revision", revision).put("sizeBytes", bytes.length);
        Path pending = path("current-" + UUID.randomUUID() + ".pending.json");
        writeNew(pending, canonicalBytes(pointer));
        Path current = path("current.json");
        safe(current);
        Files.move(pending, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // New independent file reads verify current pointer, byte size, document hash and schema.
        Snapshot after = readSnapshot();
        require(after.revision.equals(revision) && after.document.projects.equals(projects) && after.document.enabledMetrics.equals(metrics)
                && Objects.equals(after.document.previousRevision, previousRevision), "CONFIG_UNAVAILABLE");
        return after;
    }

    public <T> T exclusive(java.util.function.Supplier<T> action) {
        try { return locked(action::get); }
        catch (ConfigurationException e) { throw e; }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw sanitized(e, "CONFIG_UNAVAILABLE"); }
    }

    public boolean collectionBusy() {
        try { return locked(() -> false); }
        catch (ConfigurationException e) { if (e.code.equals("CONFIG_STALE")) return true; throw e; }
        catch (Exception e) { throw sanitized(e, "CONFIG_UNAVAILABLE"); }
    }

    private <T> T locked(LockedOperation<T> operation) throws Exception {
        require(IN_PROCESS_LOCKS.add(directory), "CONFIG_STALE");
        try { return lockedFile(operation); } finally { IN_PROCESS_LOCKS.remove(directory); }
    }

    private <T> T lockedFile(LockedOperation<T> operation) throws Exception {
        safe(directory);
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "CONFIG_UNAVAILABLE");
        Path lockPath = path("configuration.lock");
        safe(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            try (FileLock lock = channel.tryLock()) {
                require(lock != null, "CONFIG_STALE");
                safe(lockPath);
                return operation.run();
            } catch (OverlappingFileLockException failure) {
                throw new ConfigurationException("CONFIG_STALE");
            }
        }
    }

    private static List<Project> validateProjects(List<Project> projects) {
        require(projects != null && projects.size() <= 32, "CONFIG_INVALID");
        Set<String> ids = new HashSet<>(), accounts = new HashSet<>(), publicIds = new HashSet<>(), stableIds = new HashSet<>();
        List<Project> result = new ArrayList<>();
        for (Project project : projects) {
            require(project != null && project.id != null && project.id.matches("[a-z][a-z0-9-]{0,39}")
                    && project.name != null && !project.name.isBlank() && project.name.length() <= 80
                    && decimalId(project.advertiserId) && ids.add(project.id) && decimalId(project.storageAccountId) && accounts.add(project.storageAccountId)
                    && project.douyinIds != null && project.douyinIds.size() <= 20 && (!project.deleted || !project.enabled)
                    && project.category != null && CATEGORIES.contains(project.category)
                    && project.collectionTheme != null && Set.of("QUANYU", "CHENGFANG").contains(project.collectionTheme),
                    "CONFIG_INVALID");
            Set<String> anchors = new HashSet<>();
            for (String anchor : project.douyinIds) require(decimalId(anchor) && anchors.add(anchor), "CONFIG_INVALID");
            require((project.publicDouyinId == null && project.awemeName == null && !project.douyinIds.isEmpty())
                    || (project.publicDouyinId != null && project.publicDouyinId.matches("[A-Za-z0-9_.-]{1,64}")
                    && (project.awemeName == null || (!project.awemeName.isBlank() && project.awemeName.length() <= 200)) && project.douyinIds.size() <= 1), "CONFIG_INVALID");
            if (project.publicDouyinId != null) {
                require(publicIds.add(project.publicDouyinId), "CONFIG_IDENTITY_MISMATCH");
                if (project.douyinIds.size() == 1) require(stableIds.add(project.douyinIds.getFirst()), "CONFIG_IDENTITY_MISMATCH");
            }
            result.add(new Project(project.id, project.name, project.advertiserId, List.copyOf(project.douyinIds), project.enabled, project.publicDouyinId, project.awemeName, project.deleted, project.category, project.collectionTheme, project.storageAccountId));
        }
        return List.copyOf(result);
    }

    private void requireChangeWindow(List<Project> before, List<Project> after) {
        boolean changed = after.stream().anyMatch(p -> before.stream().filter(b -> b.id.equals(p.id))
                .anyMatch(b -> !b.collectionTheme.equals(p.collectionTheme) || !b.advertiserId.equals(p.advertiserId)
                        || !Objects.equals(b.publicDouyinId, p.publicDouyinId) || !b.douyinIds.equals(p.douyinIds)));
        int hour = java.time.ZonedDateTime.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))).getHour();
        require(!changed || hour < 8 || hour >= 12, "CONFIG_COLLECTION_WINDOW");
    }

    private void requireStableBindings(List<Project> existing, List<Project> candidate) {
        Map<String, Project> next = new HashMap<>();
        candidate.forEach(project -> next.put(project.id, project));
        for (Project old : existing) {
            Project p = next.get(old.id);
            require(p != null, "CONFIG_INVALID");
            require(old.storageAccountId.equals(p.storageAccountId), "CONFIG_IDENTITY_MISMATCH");
            boolean unchanged = old.advertiserId.equals(p.advertiserId)
                    && old.douyinIds.equals(p.douyinIds) && Objects.equals(old.publicDouyinId, p.publicDouyinId);
            if (!unchanged) {
                // An advertiser can change only within the same verified Douyin identity.
                require(p.douyinIds.size() == 1 && (old.douyinIds.size() == 1
                        ? old.douyinIds.equals(p.douyinIds)
                        : old.douyinIds.isEmpty() && old.publicDouyinId != null
                          && Objects.equals(old.publicDouyinId, p.publicDouyinId)), "CONFIG_IDENTITY_MISMATCH");
            }
        }
        var owners = new HashMap<String, String>();
        for (Lineage lineage : lineages()) for (String account : lineage.accounts)
            require(owners.putIfAbsent(account, lineage.projectId) == null
                    || owners.get(account).equals(lineage.projectId), "CONFIG_IDENTITY_MISMATCH");
        for (Project p : candidate)
            require(!owners.containsKey(p.storageAccountId) || owners.get(p.storageAccountId).equals(p.id), "CONFIG_IDENTITY_MISMATCH");
    }

    public record Lineage(String projectId, String logicalAccount, List<String> accounts) { }

    /** Read immutable history, never rewrite old source/account identities. Oldest account remains
     * the private-review/preference key so existing saved user results remain accessible. */
    public List<Lineage> lineages() {
        try {
            Snapshot current = snapshot();
            var byProject = new java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>();
            for (Project p : current.document.projects) byProject.put(p.id, new java.util.LinkedHashSet<>(List.of(p.storageAccountId)));
            Set<String> seen = new HashSet<>(Set.of(current.revision));
            String previous = current.document.previousRevision;
            while (previous != null) {
                require(seen.size() < 10000 && seen.add(previous), "CONFIG_UNAVAILABLE");
                requireHash(previous, "CONFIG_UNAVAILABLE");
                byte[] bytes = readFile(path("revisions").resolve(previous + ".json"), MAX_DOCUMENT_BYTES);
                require(sha(bytes).equals(previous), "CONFIG_UNAVAILABLE");
                JsonNode document = parse(bytes);
                for (Project p : parseProjects(document.get("projects"))) {
                    var accounts = byProject.get(p.id);
                    if (accounts != null) { accounts.remove(p.storageAccountId); accounts.add(p.storageAccountId); }
                }
                previous = document.path("previousRevision").isNull() ? null : text(document, "previousRevision", "CONFIG_UNAVAILABLE");
            }
            var result = new ArrayList<Lineage>();
            byProject.forEach((id, accounts) -> {
                var ordered = new ArrayList<>(accounts); java.util.Collections.reverse(ordered);
                result.add(new Lineage(id, ordered.getFirst(), List.copyOf(ordered)));
            });
            return List.copyOf(result);
        } catch (Exception e) { throw sanitized(e, "CONFIG_UNAVAILABLE"); }
    }

    public Lineage lineage(String projectId) {
        return lineages().stream().filter(p -> p.projectId.equals(projectId)).findFirst()
                .orElseThrow(() -> new ConfigurationException("CONFIG_INVALID"));
    }

    private static boolean decimalId(String value) { return value != null && value.matches("[1-9][0-9]{0,29}"); }

    private static String candidateHash(List<Project> projects, List<String> metrics) {
        try { return sha(canonicalBytes(projectContent(projects, metrics))); }
        catch (Exception failure) { throw sanitized(failure, "CONFIG_UNAVAILABLE"); }
    }

    private static ObjectNode projectContent(List<Project> projects, List<String> metrics) {
        ObjectNode content = JSON.createObjectNode().put("metricProfile", METRIC_PROFILE);
        ArrayNode selected = content.putArray("enabledMetrics");
        metrics.forEach(selected::add);
        ArrayNode array = content.putArray("projects");
        for (Project project : projects) {
            ObjectNode row = array.addObject().put("id", project.id).put("name", project.name)
                    .put("advertiserId", project.advertiserId).put("storageAccountId", project.storageAccountId).put("enabled", project.enabled).put("category", project.category)
                    .put("collectionTheme", project.collectionTheme);
            ArrayNode anchors = row.putArray("douyinIds");
            project.douyinIds.forEach(anchors::add);
            if (project.publicDouyinId != null) row.put("publicDouyinId", project.publicDouyinId);
            if (project.awemeName != null) row.put("awemeName", project.awemeName);
            if (project.deleted) row.put("deleted", true);
        }
        return content;
    }

    private Path path(String child) throws Exception {
        Path result = directory.resolve(child);
        safe(result);
        return result;
    }

    private void safe(Path path) throws Exception {
        require(path.isAbsolute() && path.equals(path.normalize()) && path.startsWith(localRoot) && !path.equals(localRoot),
                "CONFIG_UNAVAILABLE");
        require(localRoot.toRealPath().equals(localRoot), "CONFIG_UNAVAILABLE");
        Path cursor = localRoot;
        for (Path segment : localRoot.relativize(path)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor) && cursor.toRealPath().equals(cursor), "CONFIG_UNAVAILABLE");
            }
        }
    }

    private void createDirectories(Path target) throws Exception {
        safe(target);
        Path cursor = localRoot;
        for (Path part : localRoot.relativize(target)) {
            cursor = cursor.resolve(part);
            safe(cursor);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cursor);
            require(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS), "CONFIG_UNAVAILABLE");
            safe(cursor);
        }
    }

    private byte[] readFile(Path path, int limit) throws Exception {
        safe(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= limit, "CONFIG_UNAVAILABLE");
        byte[] bytes = Files.readAllBytes(path);
        require(bytes.length <= limit, "CONFIG_UNAVAILABLE");
        safe(path);
        return bytes;
    }

    private void writeNew(Path path, byte[] bytes) throws Exception {
        safe(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        safe(path);
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            JsonNode node = JSON.readTree(bytes);
            require(node != null && node.isObject(), "CONFIG_UNAVAILABLE");
            return node;
        } catch (Exception failure) { throw new ConfigurationException("CONFIG_UNAVAILABLE"); }
    }

    private static byte[] canonicalBytes(JsonNode node) { return JSON.writeValueAsBytes(canonical(node)); }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            node.properties().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JSON.createArrayNode();
            for (JsonNode child : node) sorted.add(canonical(child));
            return sorted;
        }
        return node.deepCopy();
    }

    private static String text(JsonNode node, String field, String code) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual(), code);
        return value.stringValue();
    }

    private static void keys(JsonNode node, Set<String> expected, String code) {
        require(node != null && node.isObject() && node.properties().stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(expected), code);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void requireHash(String value, String code) { require(value != null && value.matches("[a-f0-9]{64}"), code); }
    private static List<String> validateMetrics(List<String> metrics) {
        try { return MetricSelection.validate(metrics); }
        catch (Exception failure) { throw new ConfigurationException("CONFIG_INVALID"); }
    }
    private static void require(boolean valid, String code) { if (!valid) throw new ConfigurationException(code); }
    private static ConfigurationException sanitized(Exception failure, String fallback) {
        return new ConfigurationException(failure instanceof ConfigurationException known ? known.code : fallback);
    }

    private static String optionalText(JsonNode node, String key) {
        return !node.has(key) || node.get(key).isNull() ? null : text(node, key, "CONFIG_INVALID");
    }
    public record Project(String id, String name, String advertiserId, List<String> douyinIds, boolean enabled,
                          String publicDouyinId, String awemeName, boolean deleted, String category, String collectionTheme, String storageAccountId) {
        public Project(String id, String name, String advertiserId, List<String> douyinIds, boolean enabled,
                       String publicDouyinId, String awemeName, boolean deleted, String category, String collectionTheme) {
            this(id, name, advertiserId, douyinIds, enabled, publicDouyinId, awemeName, deleted, category, collectionTheme, advertiserId);
        }
        public Project(String id, String name, String advertiserId, List<String> douyinIds, boolean enabled,
                       String publicDouyinId, String awemeName, boolean deleted, String category) {
            this(id, name, advertiserId, douyinIds, enabled, publicDouyinId, awemeName, deleted, category, "QUANYU");
        }
        public Project(String id, String name, String advertiserId, List<String> douyinIds, boolean enabled,
                       String publicDouyinId, String awemeName, boolean deleted) {
            this(id, name, advertiserId, douyinIds, enabled, publicDouyinId, awemeName, deleted, "OTHER");
        }
        public Project(String id, String name, String advertiserId, List<String> douyinIds, boolean enabled) {
            this(id, name, advertiserId, douyinIds, enabled, null, null, false);
        }
        public Project(String id, String name, String advertiserId, List<String> douyinIds, boolean enabled, String publicDouyinId, String awemeName) {
            this(id, name, advertiserId, douyinIds, enabled, publicDouyinId, awemeName, false);
        }
        @Override public String toString() { return "Project[redacted]"; }
    }
    public record Document(String schemaVersion, String createdAt, String previousRevision, String actor,
                           String metricProfile, List<Project> projects, List<String> enabledMetrics) { }
    public record Snapshot(String revision, Document document) { }
    public record Preflight(String status, String expectedRevision, String candidateHash) { }
    public record Applied(String status, String revision, String previousRevision, String collectorExecution) { }
    public static final class ConfigurationException extends RuntimeException {
        private final String code;
        private ConfigurationException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    @FunctionalInterface private interface LockedOperation<T> { T run() throws Exception; }
}
