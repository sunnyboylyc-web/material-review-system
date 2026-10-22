package cn.datadashboard.api;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.configuration.MetricSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationControllerTest {
    @TempDir Path workspace;
    final JsonMapper mapper = new JsonMapper();
    ProjectConfigurationStore setup() throws Exception {
        Files.createDirectories(workspace.resolve(".local"));
        var store = new ProjectConfigurationStore(workspace, workspace.resolve(".local/configuration"));
        store.initialize(List.of(new ProjectConfigurationStore.Project("synthetic", "合成项目", "10000001", List.of("20000001"), true)));
        return store;
    }
    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "127.0.0.1:5180");
        request.addHeader("Origin", "http://127.0.0.1:5180");
        request.addHeader("X-SCSJ-Config-Write", "1");
        return request;
    }
    @Test void readOnlyModeRejectsWriteAndDoesNotChangeRevision() throws Exception {
        var store = setup(); var before = store.snapshot().revision();
        var controller = new ConfigurationController(store, false);
        assertEquals(false, controller.current(request()).getBody().get("writeEnabled"));
        assertThrows(ConfigurationController.GuardFailure.class, () -> controller.preflight("{}", request()));
        assertEquals(before, store.snapshot().revision());
    }
    @Test void categoryRoundTripsThroughHttpBoundaryAndInvalidValueCannotPublish() throws Exception {
        var store = setup(); var controller = new ConfigurationController(store, true);
        var before = store.snapshot();
        var changed = List.of(new ProjectConfigurationStore.Project("synthetic", "合成项目", "10000001", List.of("20000001"), true, null, null, false, "SMALL_APPLIANCE"));
        var checked = (ProjectConfigurationStore.Preflight) controller.preflight(mapper.writeValueAsString(Map.of(
                "expectedRevision", before.revision(), "projects", changed)), request());
        controller.apply(mapper.writeValueAsString(Map.of("expectedRevision", before.revision(), "projects", changed,
                "candidateHash", checked.candidateHash())), request());
        var current = mapper.valueToTree(controller.current(request()).getBody());
        assertEquals("SMALL_APPLIANCE", current.path("projects").get(0).path("category").asText());
        String invalid = mapper.writeValueAsString(Map.of("expectedRevision", store.snapshot().revision(), "projects", changed))
                .replace("SMALL_APPLIANCE", "invalid");
        assertThrows(ProjectConfigurationStore.ConfigurationException.class, () -> controller.preflight(invalid, request()));
        assertEquals("SMALL_APPLIANCE", store.snapshot().document().projects().getFirst().category());
    }
    @Test void rejectsCrossOriginRebindingAndMissingCustomHeader() throws Exception {
        var store = setup(); var before = store.snapshot().revision();
        var controller = new ConfigurationController(store, true);
        for (String header : List.of("Host", "Origin", "X-SCSJ-Config-Write", "Sec-Fetch-Site")) {
            var request = request(); request.removeHeader(header);
            request.addHeader(header, header.equals("Sec-Fetch-Site") ? "cross-site" : "untrusted.example");
            assertThrows(ConfigurationController.GuardFailure.class, () -> controller.preflight("{}", request));
        }
        var remote = request(); remote.setRemoteAddr("10.0.0.1");
        assertThrows(ConfigurationController.GuardFailure.class, () -> controller.current(remote));
        var noOrigin = request(); noOrigin.removeHeader("Origin");
        assertThrows(ConfigurationController.GuardFailure.class, () -> controller.preflight("{}", noOrigin));
        assertEquals(before, store.snapshot().revision());
    }
    @Test void preflightApplyAndRereadHaveOneRevisionWithoutExposingPaths() throws Exception {
        var store = setup(); var before = store.snapshot().revision();
        var controller = new ConfigurationController(store, true);
        var changed = List.of(new ProjectConfigurationStore.Project("synthetic", "更名", "10000001", List.of("20000001"), false));
        String body = mapper.writeValueAsString(Map.of("expectedRevision", before, "projects", changed));
        var check = (ProjectConfigurationStore.Preflight) controller.preflight(body, request());
        assertEquals(before, store.snapshot().revision());
        var applied = (ProjectConfigurationStore.Applied) controller.apply(mapper.writeValueAsString(Map.of(
                "expectedRevision", before, "projects", changed, "candidateHash", check.candidateHash())), request());
        assertEquals("CURRENT_VERIFIED", applied.status());
        assertEquals(applied.revision(), controller.current(request()).getBody().get("revision"));
        assertFalse(mapper.writeValueAsString(controller.current(request()).getBody()).contains(workspace.toString()));
        assertThrows(ProjectConfigurationStore.ConfigurationException.class, () -> controller.preflight(body, request()));
    }
    @Test void unexpectedFieldsAndDuplicateFieldsAreRejected() throws Exception {
        var controller = new ConfigurationController(setup(), true);
        assertThrows(RuntimeException.class, () -> controller.preflight("{\"expectedRevision\":\"a\",\"projects\":[],\"metricProfile\":\"other\"}", request()));
        assertThrows(RuntimeException.class, () -> controller.preflight("{\"expectedRevision\":\"a\",\"expectedRevision\":\"b\",\"projects\":[]}", request()));
    }

    @Test void metricSelectionSharesRevisionAndLegacyProjectEditsPreserveIt() throws Exception {
        var store = setup();
        var controller = new ConfigurationController(store, true);
        var before = store.snapshot();
        var selected = MetricSelection.allMetrics().stream()
                .filter(metric -> !metric.equals("video_like_count_for_roi2")).toList();
        var projects = before.document().projects();
        String body = mapper.writeValueAsString(Map.of("expectedRevision", before.revision(),
                "projects", projects, "enabledMetrics", selected));
        var checked = (ProjectConfigurationStore.Preflight) controller.preflight(body, request());
        assertEquals(before.revision(), store.snapshot().revision());
        var applied = (ProjectConfigurationStore.Applied) controller.apply(mapper.writeValueAsString(Map.of(
                "expectedRevision", before.revision(), "projects", projects, "enabledMetrics", selected,
                "candidateHash", checked.candidateHash())), request());
        var read = controller.current(request()).getBody();
        assertEquals(applied.revision(), read.get("revision"));
        assertEquals(selected, read.get("enabledMetrics"));
        assertEquals(true, read.get("metricChangesEnabled"));
        assertEquals(28, ((List<?>) read.get("metricCatalog")).size());
        String legacy = mapper.writeValueAsString(Map.of("expectedRevision", applied.revision(), "projects", projects));
        var legacyCheck = (ProjectConfigurationStore.Preflight) controller.preflight(legacy, request());
        controller.apply(mapper.writeValueAsString(Map.of("expectedRevision", applied.revision(), "projects", projects,
                "candidateHash", legacyCheck.candidateHash())), request());
        assertEquals(selected, store.snapshot().document().enabledMetrics());
    }

    @Test void metricInvalidOrChangedAfterPreflightNeverPublishes() throws Exception {
        var store = setup(); var controller = new ConfigurationController(store, true);
        var before = store.snapshot(); var projects = before.document().projects();
        for (Object invalid : List.of(List.of("stat_cost_for_roi2"), List.of("invented_metric"), "not-array")) {
            assertThrows(RuntimeException.class, () -> controller.preflight(mapper.writeValueAsString(Map.of(
                    "expectedRevision", before.revision(), "projects", projects, "enabledMetrics", invalid)), request()));
            assertEquals(before.revision(), store.snapshot().revision());
        }
        var selected = MetricSelection.allMetrics();
        var check = (ProjectConfigurationStore.Preflight) controller.preflight(mapper.writeValueAsString(Map.of(
                "expectedRevision", before.revision(), "projects", projects, "enabledMetrics", selected)), request());
        var changed = selected.stream().filter(metric -> !metric.equals("video_like_count_for_roi2")).toList();
        assertThrows(RuntimeException.class, () -> controller.apply(mapper.writeValueAsString(Map.of(
                "expectedRevision", before.revision(), "projects", projects, "enabledMetrics", changed,
                "candidateHash", check.candidateHash())), request()));
        assertEquals(before.revision(), store.snapshot().revision());
    }
}
