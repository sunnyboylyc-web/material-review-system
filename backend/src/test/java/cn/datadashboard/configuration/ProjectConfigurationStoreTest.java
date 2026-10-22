package cn.datadashboard.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static cn.datadashboard.configuration.ProjectConfigurationStore.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectConfigurationStoreTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path workspace;
    private Path directory;
    private ProjectConfigurationStore store;

    @BeforeEach
    void createPrivateWorkspaceOnly() throws Exception {
        Files.createDirectory(workspace.resolve(".local"));
        directory = workspace.resolve(".local/configuration/project");
        store = new ProjectConfigurationStore(workspace, directory);
    }

    @Test
    void categoryIsValidatedHashedPersistedAndDoesNotChangeAccountIdentity() throws Exception {
        var initial = store.initialize(List.of(project("alpha", "1001", true)));
        assertThat(initial.document().projects().getFirst().category()).isEqualTo("OTHER");
        var small = List.of(new Project("alpha", "Alpha", "1001", List.of("21"), true, null, null, false, "SMALL_APPLIANCE"));
        var large = List.of(new Project("alpha", "Alpha", "1001", List.of("21"), true, null, null, false, "LARGE_APPLIANCE"));
        var checked = store.preflight(initial.revision(), small);
        assertThat(checked.candidateHash()).isNotEqualTo(store.preflight(initial.revision(), large).candidateHash());
        assertCode(() -> store.apply(initial.revision(), large, checked.candidateHash()), "CONFIG_INVALID");
        store.apply(initial.revision(), small, checked.candidateHash());
        var persisted = new ProjectConfigurationStore(workspace, directory).snapshot();
        assertThat(persisted.document().projects()).isEqualTo(small);
        assertThat(persisted.document().projects().getFirst().advertiserId()).isEqualTo("1001");
        for (String invalid : new String[]{null, "SMALL", "small_appliance", ""}) {
            var bad = List.of(new Project("alpha", "Alpha", "1001", List.of("21"), true, null, null, false, invalid));
            assertCode(() -> store.preflight(persisted.revision(), bad), "CONFIG_INVALID");
        }
    }

    @Test
    void legacyMissingCategoryLoadsAsOtherWithoutRewritingBytesAndNullIsRejected() throws Exception {
        var initial = store.initialize(List.of(project("alpha", "1001", true)));
        ObjectNode legacy = (ObjectNode) JSON.readTree(Files.readAllBytes(version(initial.revision())));
        ((ObjectNode) legacy.path("projects").get(0)).remove("category");
        byte[] bytes = JSON.writeValueAsBytes(legacy);
        String revision = sha(bytes);
        Files.write(version(revision), bytes);
        Files.write(directory.resolve("current.json"), JSON.writeValueAsBytes(JSON.createObjectNode()
                .put("schemaVersion", "project-configuration-pointer-v1").put("revision", revision).put("sizeBytes", bytes.length)));
        assertThat(store.snapshot().document().projects().getFirst().category()).isEqualTo("OTHER");
        assertThat(Files.readAllBytes(version(revision))).containsExactly(bytes);
        ((ObjectNode) legacy.path("projects").get(0)).putNull("category");
        assertCode(() -> parseProjects(legacy.get("projects")), "CONFIG_INVALID");
    }

    @Test
    void initializationIsExplicitImmutableAndSurvivesNewStoreInstance() throws Exception {
        assertCode(store::snapshot, "CONFIG_UNAVAILABLE");
        assertThat(Files.exists(directory)).isFalse();
        Snapshot initial = store.initialize(List.of(project("alpha", "1001", true)));
        assertThat(initial.revision()).matches("[a-f0-9]{64}");
        assertThat(initial.document().schemaVersion()).isEqualTo(SCHEMA_VERSION);
        assertThat(initial.document().metricProfile()).isEqualTo(METRIC_PROFILE);
        assertThat(initial.document().createdAt()).endsWith("Z");
        assertThat(initial.document().previousRevision()).isNull();
        assertThat(initial.document().actor()).isEqualTo("LOCAL_UNAUTHENTICATED");
        byte[] version = Files.readAllBytes(version(initial.revision()));
        JsonNode pointer = JSON.readTree(Files.readAllBytes(directory.resolve("current.json")));
        assertThat(sha(version)).isEqualTo(initial.revision());
        assertThat(pointer.get("sizeBytes").longValue()).isEqualTo(version.length);
        assertThat(new ProjectConfigurationStore(workspace, directory).snapshot()).isEqualTo(initial);
        assertCode(() -> store.initialize(List.of()), "CONFIG_STALE");
        assertThat(Files.readAllBytes(version(initial.revision()))).containsExactly(version);
    }

    @Test
    void metricSelectionSharesRevisionAndProjectOnlyEditsPreserveIt() throws Exception {
        var projects = List.of(project("alpha", "1001", true));
        Snapshot initial = store.initialize(projects);
        List<String> selected = MetricSelection.validate(MetricSelection.requiredMetrics());
        var checked = store.preflight(initial.revision(), projects, selected);
        assertThat(store.snapshot()).isEqualTo(initial);
        var applied = store.apply(initial.revision(), projects, selected, checked.candidateHash());
        Snapshot reduced = new ProjectConfigurationStore(workspace, directory).snapshot();
        assertThat(reduced.revision()).isEqualTo(applied.revision());
        assertThat(reduced.document().enabledMetrics()).isEqualTo(selected);
        assertThat(reduced.document().previousRevision()).isEqualTo(initial.revision());
        var renamed = List.of(new Project("alpha", "Renamed", "1001", List.of("21"), true));
        var projectOnly = store.preflight(reduced.revision(), renamed);
        store.apply(reduced.revision(), renamed, projectOnly.candidateHash());
        assertThat(store.snapshot().document().enabledMetrics()).isEqualTo(selected);
        assertCode(() -> store.preflight(initial.revision(), projects, MetricSelection.allMetrics()), "CONFIG_STALE");
        var current = store.snapshot();
        var reenable = store.preflight(current.revision(), renamed, MetricSelection.allMetrics());
        store.apply(current.revision(), renamed, MetricSelection.allMetrics(), reenable.candidateHash());
        assertThat(store.snapshot().document().enabledMetrics()).isEqualTo(MetricSelection.allMetrics());
        assertThat(Files.exists(version(initial.revision()))).isTrue();
        assertThat(Files.exists(version(reduced.revision()))).isTrue();
    }

    @Test
    void invalidMetricChangesOrChangedSelectionAfterPreflightCannotPublish() {
        var projects = List.of(project("alpha", "1001", true));
        var initial = store.initialize(projects);
        var selected = MetricSelection.requiredMetrics();
        var checked = store.preflight(initial.revision(), projects, selected);
        assertCode(() -> store.apply(initial.revision(), projects, MetricSelection.allMetrics(), checked.candidateHash()), "CONFIG_INVALID");
        assertCode(() -> store.preflight(initial.revision(), projects, List.of("stat_cost_for_roi2")), "CONFIG_INVALID");
        assertThat(store.snapshot()).isEqualTo(initial);
    }

    @Test
    void legacyV1BytesRemainUnchangedAndNextWriteExplicitlyPublishesV2() throws Exception {
        var projects = List.of(project("alpha", "1001", true));
        Snapshot seed = store.initialize(projects);
        ObjectNode legacy = (ObjectNode) JSON.readTree(Files.readAllBytes(version(seed.revision())));
        legacy.put("schemaVersion", "project-configuration-v1");
        legacy.remove("enabledMetrics");
        byte[] bytes = JSON.writeValueAsBytes(legacy);
        String revision = sha(bytes);
        Files.write(version(revision), bytes);
        JSON.writeValue(directory.resolve("current.json"), JSON.createObjectNode()
                .put("schemaVersion", "project-configuration-pointer-v1").put("revision", revision).put("sizeBytes", bytes.length));
        Snapshot before = store.snapshot();
        assertThat(before.document().schemaVersion()).isEqualTo("project-configuration-v1");
        assertThat(before.document().enabledMetrics()).isEqualTo(MetricSelection.allMetrics());
        var checked = store.preflight(revision, projects);
        store.apply(revision, projects, checked.candidateHash());
        assertThat(store.snapshot().document().schemaVersion()).isEqualTo(SCHEMA_VERSION);
        assertThat(store.snapshot().document().previousRevision()).isEqualTo(revision);
        assertThat(Files.readAllBytes(version(revision))).containsExactly(bytes);
    }

    @Test
    void preflightHashIsStableWithoutTimestampAndApplyIndependentlyPublishesTheCandidate() throws Exception {
        Snapshot initial = store.initialize(List.of(project("alpha", "1001", true)));
        List<Project> candidate = List.of(new Project("alpha", "Changed name", "1001", List.of("21"), false));
        byte[] pointerBefore = Files.readAllBytes(directory.resolve("current.json"));
        var checked = store.preflight(initial.revision(), candidate);
        assertThat(store.preflight(initial.revision(), candidate)).isEqualTo(checked);
        assertThat(checked.status()).isEqualTo("READY");
        assertThat(Files.readAllBytes(directory.resolve("current.json"))).containsExactly(pointerBefore);
        Applied applied = store.apply(initial.revision(), candidate, checked.candidateHash());
        assertThat(applied.status()).isEqualTo("CURRENT_VERIFIED");
        assertThat(applied.collectorExecution()).isEqualTo("DISABLED");
        assertThat(applied.previousRevision()).isEqualTo(initial.revision());
        assertThat(applied.revision()).isNotEqualTo(checked.candidateHash()).isNotEqualTo(initial.revision());
        assertThat(store.snapshot().document().projects()).isEqualTo(candidate);
        assertThat(store.snapshot().document().previousRevision()).isEqualTo(initial.revision());
        assertThat(store.snapshot().document().actor()).isEqualTo("LOCAL_UNAUTHENTICATED");
        assertThat(Files.exists(version(initial.revision()))).isTrue();
    }

    @Test
    void previousProjectValuesCanBeResubmittedThroughTheSamePreflightAndApply() {
        Snapshot first = store.initialize(List.of(project("alpha", "1001", true)));
        List<Project> disabled = List.of(project("alpha", "1001", false));
        Applied second = store.apply(first.revision(), disabled, store.preflight(first.revision(), disabled).candidateHash());
        var rollbackCheck = store.preflight(second.revision(), first.document().projects());
        var restored = store.apply(second.revision(), first.document().projects(), rollbackCheck.candidateHash());
        assertThat(restored.previousRevision()).isEqualTo(second.revision());
        assertThat(store.snapshot().document().previousRevision()).isEqualTo(second.revision());
        assertThat(store.snapshot().document().projects()).isEqualTo(first.document().projects());
        assertThat(Files.exists(version(first.revision()))).isTrue();
        assertThat(Files.exists(version(second.revision()))).isTrue();
    }

    @Test
    void supportsZeroProjectsAndZeroEnabledProjectsWithoutDeletingIdentities() {
        Snapshot empty = store.initialize(List.of());
        assertThat(empty.document().projects()).isEmpty();
        List<Project> added = List.of(project("alpha", "1001", false), project("beta", "1002", false));
        Applied applied = store.apply(empty.revision(), added, store.preflight(empty.revision(), added).candidateHash());
        assertThat(store.snapshot().document().projects()).allMatch(project -> !project.enabled()).hasSize(2);
        assertCode(() -> store.preflight(applied.revision(), List.of()), "CONFIG_INVALID");
    }

    @Test
    void existingIdAndAccountCannotBeChangedRemovedOrReused() throws Exception {
        Snapshot initial = store.initialize(List.of(project("alpha", "1001", true)));
        byte[] before = Files.readAllBytes(directory.resolve("current.json"));
        for (List<Project> invalid : List.of(
                List.<Project>of(),
                List.of(project("beta", "1001", true)),
                List.of(project("alpha", "1001", true), project("beta", "1001", true)))) {
            assertCode(() -> store.preflight(initial.revision(), invalid), "CONFIG_INVALID");
        }
        assertThat(Files.readAllBytes(directory.resolve("current.json"))).containsExactly(before);
    }

    @Test
    void rejectsMalformedProjectsBoundsAndDuplicateAnchorsWithoutCreatingFiles() {
        for (Project invalid : List.of(
                project("Alpha", "1001", true), project("1alpha", "1001", true),
                project("a".repeat(41), "1001", true), project("alpha", "abc", true),
                project("alpha", "0", true), project("alpha", "01001", true),
                project("alpha", "1".repeat(31), true),
                new Project("alpha", " ", "1001", List.of("1"), true),
                new Project("alpha", "n".repeat(81), "1001", List.of("1"), true),
                new Project("alpha", "Alpha", "1001", List.of(), true),
                new Project("alpha", "Alpha", "1001", List.of("1", "1"), true),
                new Project("alpha", "Alpha", "1001", List.of("0"), true),
                new Project("alpha", "Alpha", "1001", List.of("021"), true),
                new Project("alpha", "Alpha", "1001", List.of("not-decimal"), true))) {
            assertCode(() -> store.initialize(List.of(invalid)), "CONFIG_INVALID");
        }
        List<Project> oversized = new ArrayList<>();
        for (int index = 0; index < 33; index++) oversized.add(project("p-" + index, "" + (1000 + index), true));
        assertCode(() -> store.initialize(oversized), "CONFIG_INVALID");
        assertCode(() -> store.initialize(List.of(project("alpha", "1001", true), project("alpha", "1002", true))), "CONFIG_INVALID");
        assertCode(() -> store.initialize(null), "CONFIG_INVALID");
        assertThat(Files.exists(directory)).isFalse();
    }

    @Test
    void parserRejectsUnknownFieldsWrongTypesAndNonObjectRows() {
        for (String raw : List.of(
                "[{}]", "[null]", "{}",
                "[{\"id\":\"alpha\",\"name\":\"Alpha\",\"advertiserId\":1001,\"douyinIds\":[\"1\"],\"enabled\":true}]",
                "[{\"id\":\"alpha\",\"name\":\"Alpha\",\"advertiserId\":\"1001\",\"douyinIds\":[1],\"enabled\":true}]",
                "[{\"id\":\"alpha\",\"name\":\"Alpha\",\"advertiserId\":\"1001\",\"douyinIds\":[\"1\"],\"enabled\":\"true\"}]",
                "[{\"id\":\"alpha\",\"name\":\"Alpha\",\"advertiserId\":\"1001\",\"douyinIds\":[\"1\"],\"enabled\":true,\"unknown\":1}]")) {
            JsonNode parsed = JSON.readTree(raw);
            assertCode(() -> ProjectConfigurationStore.parseProjects(parsed), "CONFIG_INVALID");
        }
    }

    @Test
    void rejectsStaleRevisionAndCandidateChangedAfterPreflightWithoutPublishing() throws Exception {
        Snapshot first = store.initialize(List.of(project("alpha", "1001", true)));
        List<Project> disabled = List.of(project("alpha", "1001", false));
        var checked = store.preflight(first.revision(), disabled);
        assertCode(() -> store.apply(first.revision(), first.document().projects(), checked.candidateHash()), "CONFIG_INVALID");
        var current = store.apply(first.revision(), disabled, checked.candidateHash());
        byte[] before = Files.readAllBytes(directory.resolve("current.json"));
        assertCode(() -> store.apply(first.revision(), disabled, checked.candidateHash()), "CONFIG_STALE");
        assertCode(() -> store.preflight(first.revision(), disabled), "CONFIG_STALE");
        assertThat(store.snapshot().revision()).isEqualTo(current.revision());
        assertThat(Files.readAllBytes(directory.resolve("current.json"))).containsExactly(before);
    }

    @Test
    void exactFileLockRejectsConcurrentPublicationAndOneCasWriterWins() throws Exception {
        Snapshot initial = store.initialize(List.of(project("alpha", "1001", true)));
        List<Project> disabled = List.of(project("alpha", "1001", false));
        var checked = store.preflight(initial.revision(), disabled);
        try (FileChannel channel = FileChannel.open(directory.resolve("configuration.lock"), StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertCode(() -> store.apply(initial.revision(), disabled, checked.candidateHash()), "CONFIG_STALE");
            assertThat(store.snapshot().revision()).isEqualTo(initial.revision());
        }
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<String> attempt = () -> {
            start.await();
            try { return store.apply(initial.revision(), disabled, checked.candidateHash()).status(); }
            catch (ConfigurationException failure) { return failure.code(); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(attempt, attempt));
            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                    .containsExactlyInAnyOrder("CURRENT_VERIFIED", "CONFIG_STALE");
        }
    }

    @Test
    void sourceListsAreDefensivelyCopied() {
        List<String> anchors = new ArrayList<>(List.of("1"));
        List<Project> projects = new ArrayList<>(List.of(new Project("alpha", "Alpha", "1001", anchors, true)));
        Snapshot initial = store.initialize(projects);
        anchors.add("2");
        projects.clear();
        assertThat(initial.document().projects().getFirst().douyinIds()).containsExactly("1");
        assertThat(store.snapshot()).isEqualTo(initial);
        assertThatThrownBy(() -> initial.document().projects().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void modifiedVersionHashSizeAndDuplicateJsonCannotReadAsValid() throws Exception {
        Snapshot initial = store.initialize(List.of(project("alpha", "1001", true)));
        byte[] original = Files.readAllBytes(version(initial.revision()));
        Files.writeString(version(initial.revision()), "{}", StandardCharsets.UTF_8);
        assertCode(store::snapshot, "CONFIG_UNAVAILABLE");
        Files.write(version(initial.revision()), original);
        String pointer = Files.readString(directory.resolve("current.json"));
        Files.writeString(directory.resolve("current.json"), pointer.replace("\"sizeBytes\":", "\"sizeBytes\":1,\"sizeBytes\":"));
        assertCode(store::snapshot, "CONFIG_UNAVAILABLE");
        Files.writeString(directory.resolve("current.json"), pointer + "{}");
        assertCode(store::snapshot, "CONFIG_UNAVAILABLE");
    }

    @Test
    void signedButUnsupportedDocumentSchemaOrFieldsAreStillRejected() throws Exception {
        Snapshot initial = store.initialize(List.of(project("alpha", "1001", true)));
        ObjectNode original = (ObjectNode) JSON.readTree(Files.readAllBytes(version(initial.revision())));
        for (String mutation : List.of("extra", "profile", "unknown-project-field", "duplicate", "actor", "previous", "previous-type")) {
            ObjectNode changed = original.deepCopy();
            if (mutation.equals("extra")) changed.put("extra", "not-supported");
            if (mutation.equals("profile")) changed.put("metricProfile", "arbitrary-profile");
            if (mutation.equals("actor")) changed.put("actor", "UNVERIFIED_ACTOR");
            if (mutation.equals("previous")) changed.put("previousRevision", "not-a-sha256");
            if (mutation.equals("previous-type")) changed.put("previousRevision", 12);
            if (mutation.equals("unknown-project-field")) ((ObjectNode) changed.get("projects").get(0)).put("token", "synthetic-not-a-secret");
            String raw = JSON.writeValueAsString(changed);
            if (mutation.equals("duplicate")) raw = raw.replace("\"metricProfile\":", "\"metricProfile\":\"material-33-v1\",\"metricProfile\":");
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            String hash = sha(bytes);
            Files.write(version(hash), bytes);
            JSON.writeValue(directory.resolve("current.json"), JSON.createObjectNode()
                    .put("schemaVersion", "project-configuration-pointer-v1").put("revision", hash).put("sizeBytes", bytes.length));
            assertCode(store::snapshot, "CONFIG_UNAVAILABLE");
        }
    }

    @Test
    void rejectsPathsOutsidePrivateRootAndSymlinkedStore() throws Exception {
        assertCode(() -> new ProjectConfigurationStore(workspace, workspace.resolve("outside")), "CONFIG_UNAVAILABLE");
        assertCode(() -> new ProjectConfigurationStore(workspace, workspace.resolve(".local")), "CONFIG_UNAVAILABLE");
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        Path link = workspace.resolve(".local/link");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic link creation is unavailable on this host; outside-root checks already exercised");
        }
        assertCode(() -> new ProjectConfigurationStore(workspace, link.resolve("store")), "CONFIG_UNAVAILABLE");
        try (var children = Files.list(outside)) {
            assertThat(children.findAny()).isEmpty();
        }
    }

    @Test
    void fixedExceptionsAndProjectStringNeverDiscloseValues() {
        Project privateProject = new Project("Bad-Synthetic-Id", "private-name", "123456789", List.of("987654321"), true);
        assertThat(privateProject.toString()).isEqualTo("Project[redacted]");
        assertThatThrownBy(() -> store.initialize(List.of(privateProject))).satisfies(failure -> {
            assertThat(failure.getMessage()).isEqualTo("CONFIG_INVALID");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
        });
    }

    private Path version(String revision) { return directory.resolve("revisions").resolve(revision + ".json"); }
    private static Project project(String id, String account, boolean enabled) {
        return new Project(id, "Synthetic " + id, account, List.of("21"), enabled);
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void assertCode(Runnable work, String code) {
        assertThatThrownBy(work::run).isInstanceOf(ConfigurationException.class).hasMessage(code).hasNoCause();
    }
}
