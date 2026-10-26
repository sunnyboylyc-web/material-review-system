package cn.datadashboard.local;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.configuration.MetricSelection;
import cn.datadashboard.configuration.ProjectConfigurationStore.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalCollectorPlanMainTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"), ZoneOffset.UTC);
    @TempDir Path workspace;
    private Path local;

    @BeforeEach
    void prepareWorkspaceOnly() throws Exception {
        local = Files.createDirectory(workspace.resolve(".local"));
    }

    @Test
    void reclassificationDoesNotChangeCollectionIdentityOrProfile() throws Exception {
        Path directory = Files.createDirectory(local.resolve("category-config"));
        var store = new ProjectConfigurationStore(workspace, directory);
        var first = List.of(new Project("one", "合成", "10000001", List.of("20000001"), true, null, null, false, "SMALL_APPLIANCE"));
        var before = store.initialize(first);
        JsonNode oldPlan = read(execute(directory).planPath());
        var next = List.of(new Project("one", "合成", "10000001", List.of("20000001"), true, null, null, false, "LARGE_APPLIANCE"));
        var check = store.preflight(before.revision(), next);
        store.apply(before.revision(), next, check.candidateHash());
        JsonNode newPlan = read(execute(directory).planPath());
        assertThat(newPlan.get("projects")).isEqualTo(oldPlan.get("projects"));
        assertThat(newPlan.get("profile")).isEqualTo(oldPlan.get("profile"));
        assertThat(newPlan.path("revision").asText()).isNotEqualTo(oldPlan.path("revision").asText());
        assertThat(newPlan.path("collectorExecuted").asBoolean()).isFalse();
    }

    @Test
    void publicAccountPlansAlwaysResolveFreshAndDeletedProjectsAreExcluded() throws Exception {
        Path directory = Files.createDirectory(local.resolve("pending-config"));
        var store = new ProjectConfigurationStore(workspace, directory);
        store.initialize(List.of(
                new Project("pending", "待采项目", "10000001", List.of(), true, "public.one", null),
                new Project("known", "旧解析项目", "10000002", List.of("20000002"), true, "public.two", "已有名称"),
                new Project("deleted", "已删除", "10000003", List.of(), false, "public.three", null, true)));
        JsonNode plan = read(execute(directory).planPath());
        assertThat(plan.path("projects").size()).isEqualTo(2);
        for (JsonNode project : plan.path("projects")) {
            assertThat(project.path("douyinIds").isEmpty()).isTrue();
            assertThat(project.path("publicDouyinId").isString()).isTrue();
        }
    }

    @Test
    void zeroOneAndTwoEnabledProjectsProduceOnlyThosePrivateAccounts() throws Exception {
        for (int enabled = 0; enabled <= 2; enabled++) {
            Path directory = Files.createDirectory(local.resolve("config-" + enabled));
            var store = new ProjectConfigurationStore(workspace, directory);
            var snapshot = store.initialize(projects(enabled));
            var result = execute(directory);
            JsonNode plan = read(result.planPath());
            assertThat(result.enabledProjectCount()).isEqualTo(enabled);
            assertThat(result.revision()).isEqualTo(snapshot.revision());
            assertThat(plan.get("revision").stringValue()).isEqualTo(snapshot.revision());
            assertThat(plan.get("projects").size()).isEqualTo(enabled);
            assertThat(plan.get("status").stringValue()).isEqualTo("CONFIGURATION_READY_NOT_EXECUTED");
            assertThat(plan.get("networkEnabled").booleanValue()).isFalse();
            assertThat(plan.get("collectorExecuted").booleanValue()).isFalse();
            assertThat(plan.get("onlineCapabilityVerified").booleanValue()).isFalse();
            assertThat(plan.get("statDate").stringValue()).isEqualTo("2026-10-12");
            assertThat(plan.get("timeZone").stringValue()).isEqualTo("Asia/Shanghai");
            if (enabled > 0) {
                assertThat(plan.get("projects").get(0).get("advertiserId").stringValue()).isEqualTo("10000001");
                assertThat(plan.get("projects").get(0).get("douyinIds").get(0).stringValue()).isEqualTo("20000001");
            }
            if (enabled < 2) assertThat(plan.get("projects").toString()).doesNotContain("10000002", "20000002");
            JsonNode evidence = plan.get("configurationEvidence");
            Path revision = Path.of(evidence.get("absolutePath").stringValue());
            assertThat(revision).isEqualTo(directory.toRealPath().resolve("revisions").resolve(snapshot.revision() + ".json"));
            assertThat(evidence.get("sizeBytes").longValue()).isEqualTo(Files.size(revision));
            assertThat(evidence.get("sha256").stringValue()).isEqualTo(LocalFactVerifyMain.sha(Files.readAllBytes(revision)));
            assertThat(plan.get("profile").get("dimensions").size()).isEqualTo(5);
            assertThat(plan.get("profile").get("metrics").size()).isEqualTo(28);
            assertThat(plan.get("profile").get("groups").size()).isEqualTo(5);
        }
    }

    @Test
    void nextPlanReadsNewPublishedRevisionWhileEarlierPlanAndRevisionRemain() throws Exception {
        Path directory = Files.createDirectory(local.resolve("config"));
        var store = new ProjectConfigurationStore(workspace, directory);
        var before = store.initialize(projects(1));
        var first = execute(directory);
        byte[] firstBytes = Files.readAllBytes(first.planPath());
        var checked = store.preflight(before.revision(), projects(2));
        store.apply(before.revision(), projects(2), checked.candidateHash());
        var current = store.snapshot();
        var second = execute(directory);
        assertThat(second.revision()).isEqualTo(current.revision()).isNotEqualTo(first.revision());
        assertThat(second.enabledProjectCount()).isEqualTo(2);
        assertThat(first.enabledProjectCount()).isEqualTo(1);
        assertThat(Files.readAllBytes(first.planPath())).containsExactly(firstBytes);
        assertThat(Files.exists(directory.resolve("revisions").resolve(before.revision() + ".json"))).isTrue();
        assertThat(second.planPath()).isNotEqualTo(first.planPath());
    }

    @Test
    void emptyProjectDocumentIsAValidNoOpPlanNotACollectionSuccess() throws Exception {
        Path directory = Files.createDirectory(local.resolve("config"));
        new ProjectConfigurationStore(workspace, directory).initialize(List.of());
        JsonNode plan = read(execute(directory).planPath());
        assertThat(plan.get("projects").isEmpty()).isTrue();
        assertThat(plan.get("enabledProjectCount").intValue()).isZero();
        assertThat(plan.get("status").stringValue()).isEqualTo("CONFIGURATION_READY_NOT_EXECUTED");
    }

    @Test
    void cliHasOnlyFixedStatusAndPrivateReceiptPathWithoutAccountOrAnchorValues() throws Exception {
        Path directory = Files.createDirectory(local.resolve("config"));
        new ProjectConfigurationStore(workspace, directory).initialize(projects(2));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = LocalCollectorPlanMain.run(new String[]{directory.toString(), "2026-10-12", "--dry-run"},
                workspace, CLOCK, new PrintStream(out), new PrintStream(err));
        assertThat(code).isZero();
        assertThat(out.toString()).startsWith("CONFIGURATION_READY_NOT_EXECUTED" + System.lineSeparator()
                + "Local collector dry-run plan: ").doesNotContain("10000001", "20000001", "10000002", "20000002");
        assertThat(out.toString().lines().count()).isEqualTo(2);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void invalidArgumentsAndDatesReturnOnlySanitizedFailureWithoutPlan() throws Exception {
        for (String[] args : List.of(new String[]{}, new String[]{"secret-location", "2026-10-12"},
                new String[]{"secret-location", "2026-10-12", "--apply"},
                new String[]{"secret-location", "2026-10-12", "--dry-run", "extra"},
                new String[]{"secret-location", "2026-10-13", "--dry-run"},
                new String[]{"secret-location", "2026-10-14", "--dry-run"},
                new String[]{"secret-location", "2026-2-03", "--dry-run"},
                new String[]{"secret-location", "2026-02-30", "--dry-run"},
                new String[]{"secret-location", "1899-12-31", "--dry-run"})) {
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            assertThat(LocalCollectorPlanMain.run(args, workspace, CLOCK, new PrintStream(out), new PrintStream(err))).isEqualTo(2);
            assertThat(out.toString()).isEmpty();
            assertThat(err.toString()).isEqualTo("Local collector dry-run plan rejected: arguments, date, configuration or receipt validation failed." + System.lineSeparator());
        }
        assertThat(Files.exists(local.resolve("collector-plans"))).isFalse();
    }

    @Test
    void yesterdayBoundaryUsesShanghaiInsteadOfTheHostsClockZone() {
        Clock utcBeforeShanghaiMidnight = Clock.fixed(Instant.parse("2026-10-12T15:59:59Z"), ZoneOffset.UTC);
        Clock utcAfterShanghaiMidnight = Clock.fixed(Instant.parse("2026-10-12T16:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> LocalCollectorPlanMain.date("2026-10-12", utcBeforeShanghaiMidnight)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LocalCollectorPlanMain.date("2026-10-12", utcAfterShanghaiMidnight)).isEqualTo(LocalDate.of(2026, 10, 12));
        Clock later = Clock.fixed(Instant.parse("2202-01-01T01:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> LocalCollectorPlanMain.date("2201-01-01", later)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalConfigurationAndTamperedRevisionAreRejectedBeforeAnyPlan() throws Exception {
        Path external = Files.createDirectory(workspace.resolve("external-config"));
        assertThatThrownBy(() -> execute(external)).isInstanceOf(Exception.class);
        Path directory = Files.createDirectory(local.resolve("config"));
        var store = new ProjectConfigurationStore(workspace, directory);
        var snapshot = store.initialize(projects(1));
        Files.writeString(directory.resolve("revisions").resolve(snapshot.revision() + ".json"), " ", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> execute(directory)).isInstanceOf(Exception.class);
        assertThat(Files.exists(local.resolve("collector-plans"))).isFalse();
    }

    @Test
    void profileValidationRejectsMissingMetricsAndWrongGroupingWithoutNetwork() throws Exception {
        JsonNode profile;
        try (var stream = LocalCollectorPlanMain.class.getResourceAsStream("/configuration/material-33-v1.json")) {
            assertThat(stream).isNotNull();
            profile = JSON.readTree(stream.readAllBytes());
        }
        JsonNode good = profile;
        assertThatCode(() -> LocalCollectorPlanMain.validateProfile(good)).doesNotThrowAnyException();
        ObjectNode missing = ((ObjectNode) profile).deepCopy();
        ((ArrayNode) missing.get("metrics")).remove(0);
        assertThatThrownBy(() -> LocalCollectorPlanMain.validateProfile(missing)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode wrong = ((ObjectNode) profile).deepCopy();
        ((ObjectNode) wrong.get("groups").get(0)).put("pageSize", 200);
        assertThatThrownBy(() -> LocalCollectorPlanMain.validateProfile(wrong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectedMetricsAndTheirImmutableContractFollowOneRevision() throws Exception {
        Path directory = Files.createDirectory(local.resolve("selected-config"));
        var store = new ProjectConfigurationStore(workspace, directory);
        var initial = store.initialize(projects(1));
        JsonNode originalPlan = read(execute(directory).planPath());
        var selected = MetricSelection.allMetrics().stream()
                .filter(metric -> !metric.equals("video_like_count_for_roi2")).toList();
        var check = store.preflight(initial.revision(), projects(1), selected);
        var applied = store.apply(initial.revision(), projects(1), selected, check.candidateHash());
        JsonNode plan = read(execute(directory).planPath());
        assertThat(plan.path("revision").stringValue()).isEqualTo(applied.revision());
        assertThat(plan.path("profile").path("metrics").size()).isEqualTo(27);
        assertThat(plan.path("acquisitionContract").path("enabledMetrics")).isEqualTo(JSON.valueToTree(selected));
        assertThat(plan.path("acquisitionContract").path("selectionSha256").stringValue())
                .isEqualTo(MetricSelection.selectionSha256(selected));
        assertThat(plan.path("acquisitionContract").path("configurationRevision").stringValue()).isEqualTo(applied.revision());
        assertThat(plan.path("profile").path("groups").size()).isEqualTo(5);
        assertThat(originalPlan.path("profile").path("metrics").size()).isEqualTo(28);
        assertThat(originalPlan.path("acquisitionContract").path("configurationRevision").stringValue()).isEqualTo(initial.revision());
    }

    private LocalCollectorPlanMain.Result execute(Path directory) throws Exception {
        return LocalCollectorPlanMain.execute(new String[]{directory.toString(), "2026-10-12", "--dry-run"}, workspace, CLOCK);
    }

    private static List<Project> projects(int enabledCount) {
        return List.of(new Project("synthetic-one", "Synthetic One", "10000001", List.of("20000001"), enabledCount >= 1),
                new Project("synthetic-two", "Synthetic Two", "10000002", List.of("20000002"), enabledCount >= 2));
    }

    private static JsonNode read(Path path) throws Exception { return JSON.readTree(Files.readAllBytes(path)); }
}
