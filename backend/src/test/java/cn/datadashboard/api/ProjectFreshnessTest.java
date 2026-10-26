package cn.datadashboard.api;

import cn.datadashboard.calculation.DashboardModels.DayEvidence;
import cn.datadashboard.calculation.DashboardModels.Snapshot;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectFreshnessTest {
    private static DayEvidence day(String date, String source) {
        return new DayEvidence(LocalDate.parse(date), 1, "synthetic", source);
    }
    private static DashboardController controller(List<DayEvidence> days) {
        return new DashboardController(ProjectDirectory.single("100"),
                account -> new Snapshot(account, days, List.of()));
    }

    @Test void laterManualDayAndHistoricalGapsDoNotChangeLatestMcpEmptyDay() {
        var result = controller(List.of(day("2026-10-01", "MCP_AUTO"),
                day("2026-10-13", "MCP_AUTO"), day("2026-11-01", "MANUAL_HISTORY")))
                .freshness("sampleproject");
        assertEquals(LocalDate.parse("2026-10-13"), result.latestMcpStatDate());
        assertEquals("freshness-1.0.0", result.schemaVersion());
        assertEquals("sampleproject", result.projectId());
    }
    @Test void noMcpAndEmptySnapshotAreSuccessfulNull() {
        assertNull(controller(List.of(day("2026-11-01", "MANUAL_HISTORY"))).freshness("sampleproject").latestMcpStatDate());
        assertNull(controller(List.of()).freshness("sampleproject").latestMcpStatDate());
    }
    @Test void projectResolvesAccountAndRejectsMismatchedSnapshot() {
        var directory = new ProjectDirectory(() -> List.of(
                new ProjectDirectory.Binding("one", "same", "100", true),
                new ProjectDirectory.Binding("two", "same", "200", true)));
        var controller = new DashboardController(directory, account -> new Snapshot(account,
                List.of(day(account.equals("100") ? "2026-10-01" : "2026-10-13", "MCP_AUTO")), List.of()));
        assertEquals(LocalDate.parse("2026-10-01"), controller.freshness("one").latestMcpStatDate());
        assertEquals(LocalDate.parse("2026-10-13"), controller.freshness("two").latestMcpStatDate());
        var wrong = new DashboardController(directory, account -> new Snapshot("999", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> wrong.freshness("one"));
        assertThrows(ProjectDirectory.ProjectNotEnabled.class, () -> controller.freshness("absent"));
    }
    @Test void readerValidationFailureCannotBecomeSuccessfulNullOrBadRequest() {
        var controller = new DashboardController(ProjectDirectory.single("100"), account -> {
            throw new IllegalArgumentException("synthetic validation failure");
        });
        assertThrows(IllegalStateException.class, () -> controller.freshness("sampleproject"));
        assertEquals(503, controller.failedQuery().getStatusCode().value());
        assertEquals("FACT_QUERY_UNAVAILABLE", controller.failedQuery().getBody().get("code"));
        assertThrows(IllegalStateException.class, () -> new DashboardController(() -> null).freshness("sampleproject"));
    }
}
