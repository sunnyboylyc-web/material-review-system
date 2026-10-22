package cn.datadashboard.operations;

import cn.datadashboard.api.ProjectDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionStatusReaderTest {
    @TempDir Path root;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-16T02:00:00Z"), ZoneOffset.UTC);
    CollectionStatusReader reader() { return new CollectionStatusReader(root, new ProjectDirectory(() -> List.of(new ProjectDirectory.Binding("p1", "模拟项目", "SECRET_ACCOUNT", true))), clock); }
    void write(String content) throws Exception { Path file = root.resolve(".local/collection-scheduler/status.json"); Files.createDirectories(file.getParent()); Files.writeString(file, content); }
    String sample(String state, String time, String projects) { return "{\"schemaVersion\":\"collection-scheduler-v1\",\"updatedAt\":\"" + time + "\",\"statDate\":\"2026-10-15\",\"status\":\"" + state + "\",\"projects\":" + projects + "}"; }
    String project(String id) { return "{\"projectId\":\"" + id + "\",\"statDate\":\"2026-10-15\",\"status\":\"RETRY_WAIT\",\"updatedAt\":\"2026-10-16T01:59:00Z\",\"retryNotBefore\":\"2026-10-16T02:09:00Z\",\"errorCode\":\"51010\"}"; }
    @Test void backfillPreservesTwoDatesForSameProjectAndRejectsOutsideWindow() throws Exception {
        String content=sample("RUNNING","2026-10-16T01:59:00Z","["+project("p1").replace("2026-10-15","2026-10-08")+","+project("p1").replace("2026-10-15","2026-10-14")+"]");
        content=content.replace("\"projects\":","\"mode\":\"GAP_BACKFILL\",\"projects\":");
        write(content);assertEquals("OBSERVED",reader().read().get("observation"));
        assertEquals(2,((List<?>)reader().read().get("projects")).size());
        write(content.replace("2026-10-08","2026-10-07"));assertEquals("UNAVAILABLE",reader().read().get("observation"));
    }
    @Test void missingIsNotObservedAndDoesNotCreateDirectory() { assertEquals("NOT_OBSERVED", reader().read().get("observation")); assertFalse(Files.exists(root.resolve(".local"))); }
    @Test void themeConflictIsExposedAsBoundedCode() throws Exception {
        write(sample("REVIEW_REQUIRED", "2026-10-16T01:59:00Z", "[" + project("p1").replace("RETRY_WAIT", "REVIEW_REQUIRED").replace("51010", "BACKFILL_THEME_CONFLICT") + "]"));
        assertEquals("OBSERVED", reader().read().get("observation"));
        assertTrue(reader().read().toString().contains("BACKFILL_THEME_CONFLICT"));
    }
    @Test void waitIsVisibleOnlyForEnabledProjectsAndHasNoAccount() throws Exception {
        write(sample("RUNNING", "2026-10-16T01:59:00Z", "[" + project("p1") + "," + project("removed") + "]"));
        var value = reader().read(); assertEquals("OBSERVED", value.get("observation"));
        var projects = (List<?>) value.get("projects"); assertEquals(1, projects.size()); assertTrue(projects.toString().contains("模拟项目")); assertFalse(value.toString().contains("SECRET_ACCOUNT"));
    }
    @Test void onlyRunningHeartbeatExpires() throws Exception {
        write(sample("RUNNING", "2026-10-16T01:57:29Z", "[]")); assertEquals("STALE", reader().read().get("observation"));
        write(sample("RUNNING", "2026-10-16T01:57:30Z", "[]")); assertEquals("OBSERVED", reader().read().get("observation"));
        write(sample("COMPLETE", "2026-10-15T01:00:00Z", "[]")); assertEquals("OBSERVED", reader().read().get("observation"));
    }
    @Test void malformedUnknownFieldsAndOversizeFailClosed() throws Exception {
        for (String content : List.of("bad SECRET", sample("SECRET", "2026-10-16T01:59:00Z", "[]"), sample("COMPLETE", "bad", "[]"), sample("RUNNING", "2026-10-16T01:59:00Z", "[]").replace("\"projects\"", "\"secret\""), " ".repeat(65537))) {
            write(content); var result = reader().read(); assertEquals("UNAVAILABLE", result.get("observation")); assertFalse(result.toString().contains("SECRET"));
        }
    }
    @Test void duplicateProjectAndInvalidDayAreRejected() throws Exception {
        write(sample("RUNNING", "2026-10-16T01:59:00Z", "[" + project("p1") + "," + project("p1") + "]")); assertEquals("UNAVAILABLE", reader().read().get("observation"));
        write(sample("RUNNING", "2026-10-16T01:59:00Z", "[]").replace("2026-10-15", "2026-02-30")); assertEquals("UNAVAILABLE", reader().read().get("observation"));
    }
    @Test void unknownErrorTextCannotLeak() throws Exception {
        write(sample("RUNNING", "2026-10-16T01:59:00Z", "[" + project("p1").replace("51010", "SECRET_TOKEN") + "]"));
        assertTrue(reader().read().toString().contains("UNCLASSIFIED_ERROR")); assertFalse(reader().read().toString().contains("SECRET_TOKEN"));
    }
    @Test void duplicateJsonFieldsAndFutureHeartbeatAreUnavailable() throws Exception {
        write(sample("RUNNING", "2026-10-16T02:01:01Z", "[]")); assertEquals("UNAVAILABLE", reader().read().get("observation"));
        write(sample("RUNNING", "2026-10-16T02:01:00Z", "[]")); assertEquals("OBSERVED", reader().read().get("observation"));
        write(sample("RUNNING", "2026-10-16T02:00:00Z", "[]").replace("\"status\":\"RUNNING\"", "\"status\":\"COMPLETE\",\"status\":\"RUNNING\""));
        assertEquals("UNAVAILABLE", reader().read().get("observation"));
    }
    @Test void boundedPlatformLocationAndRetryCountAreVisible() throws Exception {
        for (String group : List.of("BASE", "VIDEO", "ASSIST", "RATIO1", "RATIO2")) {
            for (String suffix : List.of("1_RETRIES_0", "2_RETRIES_1", "3_RETRIES_9", "4_RETRIES_10", "99999_RETRIES_11", "100000_RETRIES_12")) {
                String code = "PLATFORM_51010_" + group + "_PAGE_" + suffix;
                write(sample("FAILED", "2026-10-16T01:59:00Z", "[" + project("p1").replace("51010", code) + "]"));
                assertEquals("OBSERVED", reader().read().get("observation"));
                assertTrue(reader().read().toString().contains(code));
            }
        }
    }
    @Test void malformedPlatformLocationCannotLeak() throws Exception {
        for (String code : List.of("PLATFORM_51010_SECRET_PAGE_1_RETRIES_1",
                "PLATFORM_51010_BASE_PAGE_0_RETRIES_1", "PLATFORM_51010_BASE_PAGE_01_RETRIES_1",
                "PLATFORM_51010_BASE_PAGE_100001_RETRIES_1", "PLATFORM_51010_BASE_PAGE_1_RETRIES_13", "PLATFORM_51010_BASE_PAGE_1_RETRIES_01",
                "PLATFORM_51010_BASE_PAGE_1_RETRIES_-1", "PLATFORM_51010_BASE_PAGE_1_RETRIES_1_SECRET_TOKEN",
                "SECRET_PLATFORM_51010_BASE_PAGE_1_RETRIES_1", "PLATFORM_51010_base_PAGE_1_RETRIES_1")) {
            write(sample("FAILED", "2026-10-16T01:59:00Z", "[" + project("p1").replace("51010", code) + "]"));
            var value = reader().read();
            assertEquals("OBSERVED", value.get("observation"));
            assertTrue(value.toString().contains("UNCLASSIFIED_ERROR"));
            assertFalse(value.toString().contains(code));
        }
    }
    @Test void symlinkRejectedWhenPlatformPermitsIt() throws Exception {
        Path directory = root.resolve(".local/collection-scheduler"); Files.createDirectories(directory);
        Path other = root.resolve("other.json"); Files.writeString(other, sample("COMPLETE", "2026-10-16T01:59:00Z", "[]"));
        try { Files.createSymbolicLink(directory.resolve("status.json"), other); }
        catch (java.io.IOException | UnsupportedOperationException denied) { org.junit.jupiter.api.Assumptions.abort("Symlink privilege unavailable"); }
        assertEquals("UNAVAILABLE", reader().read().get("observation"));
    }
}
