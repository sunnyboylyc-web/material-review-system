package cn.datadashboard.operations;

import cn.datadashboard.api.ProjectDirectory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, read-only projection of scheduler observations; never executes jobs. */
public final class CollectionStatusReader {
    private static final int MAX_BYTES = 131072;
    private static final Set<String> TOTAL = Set.of("RUNNING", "COMPLETE", "PARTIAL", "FAILED", "REVIEW_REQUIRED");
    private static final Set<String> STATES = Set.of("PENDING", "COLLECTING", "RETRY_WAIT", "SOURCE_READY", "IMPORTING", "VERIFYING", "VERIFIED", "ALREADY_PRESENT", "FAILED", "REVIEW_REQUIRED");
    private static final Set<String> ERRORS = Set.of("PROTOCOL_REJECTED", "TRANSPORT_FAILED", "COLLECTION_FAILED", "PREPARATION_FAILED", "PREFLIGHT_FAILED", "IMPORT_FAILED", "VERIFY_FAILED", "CONFIGURATION_CHANGED", "REVIEW_REQUIRED", "INTERNAL_ERROR", "51010",
            "PRECHECK_REJECTED", "UNSAFE_PATH", "UNSAFE_STATE_PATH", "STATE_IDENTITY_CHANGED", "STEP_FAILED", "COLLECTION_INTERRUPTED", "EXISTING_FACTS_NOT_OWNED", "TARGET_APPEARED_BEFORE_IMPORT", "UNSAFE_PRIVATE_PATH", "OUTPUT_ALREADY_EXISTS", "STEP_TIMEOUT", "PROCESS_FAILED", "JAVA_RECEIPT_MISSING", "DUPLICATE_ACCOUNT", "DATABASE_CHECK_FAILED", "COLLECTION_NOT_COMPLETE", "SOURCE_RECEIPT_REJECTED", "UNSAFE_SOURCE_PATH", "SOURCE_CHANGED", "SOURCE_IDENTITY_CHANGED", "SOURCE_PREFLIGHT_FAILED", "BACKUP_CHANGED", "DISK_RESERVE_REQUIRED", "BACKUP_FAILED", "IMPORT_NOT_COMPLETE", "READBACK_NOT_MATCHED",
            "BACKFILL_WINDOW_ENDED", "BACKFILL_THEME_CONFLICT", "STATE_RECORD_INVALID", "NO_ENABLED_PROJECTS", "IMPORT_CONFIG_CHANGED", "SOURCE_PUBLICATION_CHANGED");
    private final Path workspace;
    private final ProjectDirectory directory;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public CollectionStatusReader(Path workspace, ProjectDirectory directory) { this(workspace, directory, Clock.systemUTC()); }
    public CollectionStatusReader(Path workspace, ProjectDirectory directory, Clock clock) {
        this.workspace = workspace.toAbsolutePath().normalize(); this.directory = directory; this.clock = clock;
    }
    public Map<String, Object> read() {
        try {
            Path file = workspace.resolve(".local/collection-scheduler/status.json");
            for (Path p = file; p != null; p = p.getParent()) {
                if (Files.isSymbolicLink(p)) throw new IllegalArgumentException();
            }
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return empty("NOT_OBSERVED");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) throw new IllegalArgumentException();
            byte[] bytes;
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
            JsonNode node = json.readTree(bytes);
            keys(node, Set.of("schemaVersion", "updatedAt", "statDate", "status", "projects", "mode"));
            require("collection-scheduler-v1".equals(text(node, "schemaVersion")));
            String state = text(node, "status"), updated = time(node, "updatedAt"), day = date(node, "statDate");
            boolean backfill = node.has("mode") && "GAP_BACKFILL".equals(text(node,"mode"));
            require(!node.has("mode") || backfill);
            require(TOTAL.contains(state));
            require(!Instant.parse(updated).isAfter(clock.instant().plusSeconds(60)));
            var enabled = new HashMap<String, String>();
            directory.projects().forEach(p -> enabled.put((String) p.get("id"), (String) p.get("name")));
            JsonNode items = node.get("projects");
            require(items != null && items.isArray() && items.size() <= (backfill ? 224 : 32));
            var projects = new ArrayList<Map<String, Object>>();
            var seen = new HashSet<String>();
            for (JsonNode item : items) {
                keys(item, Set.of("projectId", "statDate", "status", "updatedAt", "errorCode", "retryNotBefore", "rows"));
                String id = text(item, "projectId"), projectState = text(item, "status");
                require(id.matches("[A-Za-z0-9_-]{1,80}") && seen.add(id+":"+date(item,"statDate")) && STATES.contains(projectState));
                String itemDay=date(item,"statDate");
                long age=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(itemDay),LocalDate.parse(day));
                require(backfill ? age>=1 && age<=7 : day.equals(itemDay));
                var p = new LinkedHashMap<String, Object>();
                String projectUpdated = time(item, "updatedAt");
                require(!Instant.parse(projectUpdated).isAfter(clock.instant().plusSeconds(60)));
                p.put("projectId", id); p.put("statDate", itemDay); p.put("status", projectState); p.put("updatedAt", projectUpdated);
                if (item.hasNonNull("retryNotBefore")) p.put("retryNotBefore", time(item, "retryNotBefore"));
                if (item.hasNonNull("rows")) { var rows = item.get("rows"); require(rows.isIntegralNumber() && rows.canConvertToLong() && rows.asLong() >= 0); p.put("rows", rows.asLong()); }
                if (item.hasNonNull("errorCode")) { String code = text(item, "errorCode"); p.put("errorCode", ERRORS.contains(code) || code.matches("PLATFORM_51010_(BASE|VIDEO|ASSIST|RATIO1|RATIO2)_PAGE_([1-9][0-9]{0,4}|100000)_RETRIES_([0-9]|1[0-2])") ? code : "UNCLASSIFIED_ERROR"); }
                if (enabled.containsKey(id)) { p.put("projectName", enabled.get(id)); projects.add(p); }
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("schemaVersion", "collection-scheduler-v1"); result.put("updatedAt", updated); result.put("statDate", day);
            if(backfill)result.put("mode","GAP_BACKFILL");
            result.put("status", state); result.put("projects", projects);
            boolean stale = state.equals("RUNNING") && Duration.between(Instant.parse(updated), clock.instant()).getSeconds() > 150;
            result.put("observation", stale ? "STALE" : "OBSERVED");
            return result;
        } catch (Exception failure) { return empty("UNAVAILABLE"); }
    }
    private static Map<String, Object> empty(String observation) { return Map.of("schemaVersion", "collection-scheduler-v1", "observation", observation, "projects", List.of()); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException(); }
    private static String text(JsonNode n, String key) { JsonNode value = n.get(key); require(value != null && value.isString()); return value.asString(); }
    private static String date(JsonNode n, String key) { String value = text(n, key); require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")); LocalDate.parse(value); return value; }
    private static String time(JsonNode n, String key) { String value = text(n, key); Instant.parse(value); return value; }
    private static void keys(JsonNode n, Set<String> allowed) { require(n != null && n.isObject()); for (String name : n.propertyNames()) require(allowed.contains(name)); }
}
