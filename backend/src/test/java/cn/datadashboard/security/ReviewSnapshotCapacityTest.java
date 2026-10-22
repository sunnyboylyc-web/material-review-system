package cn.datadashboard.security;

import cn.datadashboard.api.*;
import static cn.datadashboard.calculation.DashboardModels.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSnapshotCapacityTest {
    @TempDir Path root;

    @Test @Timeout(120)
    void original108000Fact90DayRegressionPersistsAndRestoresEveryFieldWithoutQuery() throws Exception {
        // The exact rotating-cohort fixture from D193ReleaseProbe, not a large
        // repeated string or a result truncated to the visible TOP rows.
        LocalDate start = LocalDate.of(2026, 1, 1);
        List<Fact> facts = new ArrayList<>();
        List<DayEvidence> days = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < 180; dayIndex++) {
            LocalDate date = start.plusDays(dayIndex);
            days.add(new DayEvidence(date, dayIndex + 1, "synthetic-" + dayIndex, "SYNTHETIC"));
            for (int id = dayIndex * 20; id < dayIndex * 20 + 600; id++) {
                String name = switch (id % 3) { case 0 -> "原创"; case 1 -> "混剪"; default -> "其他"; };
                facts.add(new Fact(date, "fixture-" + id, name + id, start.plusDays(Math.max(0, (id - 580) / 20)),
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(1000), BigDecimal.valueOf(20), BigDecimal.valueOf(2)));
            }
        }
        assertEquals(108000, facts.size());
        var projects = new ProjectDirectory(() -> List.of(new ProjectDirectory.Binding("synthetic", "test", "SYNTHETIC", true)));
        var reads = new AtomicInteger();
        var controller = new ReviewSnapshotController(root, projects, new DashboardController(projects, account -> {
            reads.incrementAndGet(); return new Snapshot(account, days, facts);
        }));
        LocalDate end = start.plusDays(179);
        var query = new ReviewSnapshotController.Query(end.minusDays(89).toString(), end.toString(),
                start.toString(), end.minusDays(90).toString(), 20);
        long began = System.nanoTime();
        var saved = controller.refresh(() -> "synthetic-user", "synthetic", query);
        double refreshMillis = (System.nanoTime() - began) / 1e6;
        assertTrue(saved.persisted(), saved.warning());
        Path file;
        try (var paths = Files.list(root)) { file = paths.findFirst().orElseThrow(); }
        long stored = Files.size(file), decoded;
        try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(file))) {
            decoded = input.transferTo(OutputStream.nullOutputStream());
        }
        assertTrue(decoded > 16L * 1024 * 1024 && decoded < ReviewSnapshotStorage.DECODED_LIMIT);
        assertTrue(stored < ReviewSnapshotStorage.STORED_LIMIT);
        var restarted = new ReviewSnapshotController(root, projects, new DashboardController(projects, account -> {
            throw new AssertionError("RESTORE_MUST_NOT_QUERY_FACTS");
        }));
        began = System.nanoTime();
        var restored = restarted.get(() -> "synthetic-user", "synthetic").getBody();
        double restoreMillis = (System.nanoTime() - began) / 1e6;
        assertNotNull(restored);
        assertEquals(saved.result(), restored.result()); // all details, curves, ratios, statuses and fingerprint
        assertEquals(saved.savedAt(), restored.savedAt());
        assertEquals(1, reads.get());
        assertEquals(204, restarted.get(() -> "another-user", "synthetic").getStatusCode().value());
        System.out.println(new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(
                "status", "D209_90_DAY_COMPLETE_SNAPSHOT_ROUNDTRIP_PASSED", "evidenceKind", "SYNTHETIC",
                "facts", facts.size(), "decodedEnvelopeBytes", decoded, "storedBytes", stored,
                "refreshAndPersistMs", refreshMillis, "restoreAndValidateMs", restoreMillis,
                "heapLimitBytes", Runtime.getRuntime().maxMemory(), "allFieldsEqual", true, "restoreFactQueries", 0)));
    }
}
