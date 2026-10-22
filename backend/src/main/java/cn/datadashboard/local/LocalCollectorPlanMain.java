package cn.datadashboard.local;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.configuration.MetricSelection;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Offline consumer of one immutable project-configuration snapshot; never invokes a collector. */
public final class LocalCollectorPlanMain {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final String PROFILE = "/configuration/material-33-v1.json";
    private static final String STATUS = "CONFIGURATION_READY_NOT_EXECUTED";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private LocalCollectorPlanMain() { }

    public static void main(String[] args) {
        int exit;
        try {
            Path current = Path.of("").toRealPath();
            Path workspace = Files.isDirectory(current.resolve("backend")) ? current : current.getParent();
            require(workspace != null && Files.isRegularFile(workspace.resolve("START_HERE.md"))
                    && Files.isRegularFile(workspace.resolve("backend/pom.xml")));
            exit = run(args, workspace, Clock.systemUTC(), System.out, System.err);
        } catch (Exception ignored) {
            System.err.println("Local collector dry-run plan rejected: workspace or local configuration unavailable.");
            exit = 2;
        }
        System.exit(exit);
    }

    static int run(String[] args, Path workspace, Clock clock, PrintStream out, PrintStream err) {
        try {
            Result result = execute(args, workspace, clock);
            out.println(STATUS);
            out.println("Local collector dry-run plan: " + result.planPath());
            return 0;
        } catch (Exception ignored) {
            // Never print account/anchor values, untrusted arguments or parser/filesystem exceptions.
            err.println("Local collector dry-run plan rejected: arguments, date, configuration or receipt validation failed.");
            return 2;
        }
    }

    static Result execute(String[] args, Path workspace, Clock clock) throws Exception {
        require(args.length == 3 && "--dry-run".equals(args[2]));
        LocalDate date = date(args[1], clock);
        Path root = workspace.toRealPath();
        Path local = root.resolve(".local").toRealPath();
        require(local.equals(root.resolve(".local")) && Files.isDirectory(local));
        Path storeDirectory = Path.of(args[0]);
        // The core owns configuration path guards and full document validation.
        var store = new ProjectConfigurationStore(root, storeDirectory);
        var snapshot = store.snapshot(); // Exactly one active snapshot per plan; no cross-revision mixing.
        require(snapshot.revision().matches("[0-9a-f]{64}")
                && "material-33-v1".equals(snapshot.document().metricProfile()));
        Path revisionPath = storeDirectory.toRealPath().resolve("revisions")
                .resolve(snapshot.revision() + ".json").toRealPath();
        require(revisionPath.startsWith(local) && Files.isRegularFile(revisionPath));
        byte[] revisionBytes = Files.readAllBytes(revisionPath);
        require(LocalFactVerifyMain.sha(revisionBytes).equals(snapshot.revision()));

        byte[] profileBytes;
        try (var stream = LocalCollectorPlanMain.class.getResourceAsStream(PROFILE)) {
            require(stream != null);
            profileBytes = stream.readAllBytes();
        }
        validateProfile(JSON.readTree(profileBytes)); // Validate the pinned base catalog, not a changing source claim.
        var selected = snapshot.document().enabledMetrics();
        JsonNode profile = MetricSelection.profile(selected);
        ObjectNode plan = JSON.createObjectNode().put("schemaVersion", "collection-plan-v1")
                .put("revision", snapshot.revision()).put("status", STATUS).put("networkEnabled", false)
                .put("collectorExecuted", false).put("onlineCapabilityVerified", false)
                .put("statDate", date.toString()).put("timeZone", SHANGHAI.getId())
                .put("createdAt", Instant.now(clock).toString());
        plan.put("meaning", "Private offline configuration plan only. It neither proves online ownership/capability nor authorizes or executes collection.");
        plan.putObject("configurationEvidence").put("absolutePath", revisionPath.toString())
                .put("sizeBytes", revisionBytes.length).put("sha256", snapshot.revision());
        plan.putObject("metricProfileEvidence").put("resource", PROFILE).put("sizeBytes", profileBytes.length)
                .put("sha256", LocalFactVerifyMain.sha(profileBytes));
        ObjectNode acquisition = plan.putObject("acquisitionContract")
                .put("catalogId", "material-33-v1")
                .put("catalogSha256", MetricSelection.catalogSha256())
                .put("selectionSha256", MetricSelection.selectionSha256(selected))
                .put("configurationRevision", snapshot.revision());
        var metrics = acquisition.putArray("enabledMetrics");
        selected.forEach(metrics::add);
        var projects = plan.putArray("projects");
        for (var project : snapshot.document().projects()) {
            if (!project.enabled() || project.deleted()) continue;
            ObjectNode entry = projects.addObject().put("id", project.id()).put("name", project.name())
                    .put("advertiserId", project.advertiserId()).put("storageAccountId", project.storageAccountId()).put("collectionTheme", project.collectionTheme());
            if (project.douyinIds().size() == 1) entry.put("expectedAwemeId", project.douyinIds().getFirst());
            var sourceAccounts = entry.putArray("sourceAccounts");
            store.lineage(project.id()).accounts().forEach(sourceAccounts::add);
            var anchors = entry.putArray("douyinIds");
            if (project.publicDouyinId() != null) entry.put("publicDouyinId", project.publicDouyinId());
            else project.douyinIds().forEach(anchors::add);
        }
        plan.put("enabledProjectCount", projects.size());
        plan.set("profile", profile);

        Path output = newPlanPath(local);
        // The revision is immutable; changes to the active pointer during this work belong to the next plan.
        require(revisionPath.toRealPath().equals(revisionPath)
                && LocalFactVerifyMain.sha(Files.readAllBytes(revisionPath)).equals(snapshot.revision()));
        Path pending = output.resolveSibling("plan.pending.json");
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan);
        Files.write(pending, bytes, StandardOpenOption.CREATE_NEW);
        require(output.getParent().toRealPath().equals(output.getParent()));
        Files.move(pending, output, StandardCopyOption.ATOMIC_MOVE);
        require(output.toRealPath().equals(output)
                && LocalFactVerifyMain.sha(Files.readAllBytes(output)).equals(LocalFactVerifyMain.sha(bytes)));
        return new Result(output, snapshot.revision(), projects.size());
    }

    static LocalDate date(String input, Clock clock) {
        require(input != null && input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        LocalDate result = LocalDate.parse(input);
        require(result.getYear() >= 1900 && result.getYear() <= 2200
                && result.isBefore(LocalDate.now(clock.withZone(SHANGHAI))));
        return result;
    }

    private static Path newPlanPath(Path local) throws Exception {
        Path runs = local.resolve("collector-plans");
        if (!Files.exists(runs)) Files.createDirectory(runs);
        require(runs.toRealPath().equals(runs) && Files.isDirectory(runs));
        Path directory = runs.resolve(UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectory(directory);
        require(directory.toRealPath().equals(directory));
        return directory.resolve("plan.json");
    }

    static void validateProfile(JsonNode profile) {
        require(profile != null && profile.isObject() && "metric-profile-v1".equals(profile.path("schemaVersion").stringValue())
                && "material-33-v1".equals(profile.path("id").stringValue())
                && profile.path("metricChangesEnabled").isBoolean() && !profile.path("metricChangesEnabled").booleanValue());
        Set<String> dimensions = fields(profile.get("dimensions"));
        Set<String> metrics = fields(profile.get("metrics"));
        require(dimensions.size() == 5 && metrics.size() == 28 && java.util.Collections.disjoint(dimensions, metrics));
        require(metrics.containsAll(fields(profile.get("currentDashboardRequiredMetrics"))));
        String sort = profile.path("sortField").stringValue();
        require("stat_cost_for_roi2".equals(sort) && metrics.contains(sort));
        JsonNode groups = profile.get("groups");
        require(groups != null && groups.isArray() && groups.size() == 5);
        Set<String> union = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode group : groups) {
            String name = group.path("name").stringValue();
            require(name != null && !name.isBlank() && names.add(name));
            Set<String> groupDimensions = fields(group.get("dimensions"));
            Set<String> groupMetrics = fields(group.get("metrics"));
            require(dimensions.containsAll(groupDimensions) && metrics.containsAll(groupMetrics)
                    && groupMetrics.contains(sort) && sort.equals(group.get("metrics").get(0).stringValue()));
            if ("base".equals(name)) require(groupDimensions.equals(dimensions) && group.path("pageSize").intValue() == 100);
            else require(groupDimensions.size() == 4 && !groupDimensions.contains("material_create_time_v2")
                    && group.path("pageSize").intValue() == 200);
            union.addAll(groupMetrics);
        }
        require(names.equals(Set.of("base", "video", "assist", "ratio1", "ratio2")) && union.equals(metrics));
    }

    private static Set<String> fields(JsonNode items) {
        require(items != null && items.isArray() && !items.isEmpty());
        Set<String> result = new HashSet<>();
        for (JsonNode item : items) require(item.isString() && !item.stringValue().isBlank() && result.add(item.stringValue()));
        return result;
    }

    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("COLLECTOR_PLAN_REJECTED"); }
    record Result(Path planPath, String revision, int enabledProjectCount) { }
}
