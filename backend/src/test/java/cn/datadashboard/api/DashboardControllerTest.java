package cn.datadashboard.api;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DashboardControllerTest {
    @Test void layeredTopAndReviewUsePreparedSnapshotButFreshnessKeepsItsOwnManifestRead() {
        var day = java.time.LocalDate.of(2026, 10, 8);
        var snapshot = new cn.datadashboard.calculation.DashboardModels.Snapshot("demo-account",
                java.util.List.of(new cn.datadashboard.calculation.DashboardModels.DayEvidence(day, 1, "a".repeat(64), "MCP_AUTO")), java.util.List.of());
        var prepared = cn.datadashboard.calculation.DashboardCalculator.prepare(snapshot);
        var rawCalls = new AtomicInteger(); var preparedCalls = new AtomicInteger();
        var controller = new DashboardController(ProjectDirectory.single("demo-account"),
                account -> { rawCalls.incrementAndGet(); return snapshot; },
                account -> { preparedCalls.incrementAndGet(); return prepared; });
        assertNotNull(controller.top("sampleproject", day.toString(), 20));
        assertNotNull(controller.review("sampleproject", day.toString(), day.toString(), day.toString(), day.toString(), 20));
        assertEquals(2, preparedCalls.get()); assertEquals(0, rawCalls.get());
        assertEquals(day, controller.freshness("sampleproject").latestMcpStatDate());
        assertEquals(1, rawCalls.get());
        assertThrows(IllegalArgumentException.class, () -> controller.top("sampleproject", "bad", 20));
        assertEquals(2, preparedCalls.get());
    }

    @Test void layeredPreparedInputCannotCrossAccountBoundary() {
        var other = cn.datadashboard.calculation.DashboardCalculator.prepare(
                new cn.datadashboard.calculation.DashboardModels.Snapshot("other-account", java.util.List.of(), java.util.List.of()));
        var controller = new DashboardController(ProjectDirectory.single("demo-account"), ignored -> null, ignored -> other);
        assertThrows(IllegalStateException.class, () -> controller.top("sampleproject", "2026-10-08", 20));
        assertThrows(IllegalStateException.class, () -> controller.review("sampleproject", "2026-10-08", "2026-10-08", "2026-10-07", "2026-10-07", 20));
    }

    @Test void rejectsBadQueryBeforeDatabaseRead() {
        var calls = new AtomicInteger();
        var controller = new DashboardController(() -> { calls.incrementAndGet(); return null; });
        assertThrows(RuntimeException.class, () -> controller.top("other", "2026-10-08", 20));
        assertThrows(IllegalArgumentException.class, () -> controller.top("sampleproject", "2026-02-30", 20));
        assertThrows(IllegalArgumentException.class, () -> controller.top("sampleproject", "2026-10-08", 201));
        assertThrows(IllegalArgumentException.class, () -> controller.review("sampleproject", "2026-10-08", "2026-10-01", "2026-09-01", "2026-09-02", 20));
        assertThrows(IllegalArgumentException.class, () -> controller.review("sampleproject", "1900-01-01", "2200-01-01", "2026-09-01", "2026-09-02", 20));
        assertEquals(0, calls.get());
    }
    @Test void projectListDoesNotExposeAccountOrCredentials() {
        var controller = new DashboardController(() -> { throw new AssertionError(); });
        assertEquals(java.util.Set.of("id", "name", "enabled", "category"), controller.projects().getFirst().keySet());
        assertEquals("OTHER", controller.projects().getFirst().get("category"));
        assertEquals("sampleproject", controller.projects().getFirst().get("id"));
        assertEquals("FACT_QUERY_UNAVAILABLE", controller.failedQuery().getBody().get("code"));
    }
}
