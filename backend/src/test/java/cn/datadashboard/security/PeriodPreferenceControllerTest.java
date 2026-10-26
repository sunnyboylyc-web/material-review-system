package cn.datadashboard.security;

import cn.datadashboard.api.ProjectDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PeriodPreferenceControllerTest {
    @TempDir Path root;
    private final ProjectDirectory projects = new ProjectDirectory(() -> List.of(
            new ProjectDirectory.Binding("p1", "one", "1", true), new ProjectDirectory.Binding("p2", "two", "2", true)));
    private final PeriodPreferenceController.Period period = new PeriodPreferenceController.Period("2026-10-01", "2026-10-07", "2026-09-01", "2026-09-07");
    @Test void preferencesAreDurableAndAccountAndProjectIsolated() {
        var controller = new PeriodPreferenceController(root, projects);
        assertEquals(204, controller.get(() -> "alice", "p1").getStatusCode().value());
        assertEquals(period, controller.put(() -> "alice", "p1", period));
        assertEquals(period, new PeriodPreferenceController(root, projects).get(() -> "alice", "p1").getBody());
        assertEquals(204, controller.get(() -> "bob", "p1").getStatusCode().value());
        assertEquals(204, controller.get(() -> "alice", "p2").getStatusCode().value());
    }
    @Test void invalidDatesUnknownProjectsAndMissingIdentityRejected() {
        var controller = new PeriodPreferenceController(root, projects);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.get(null, "p1")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.put(() -> "alice", "../unknown", period)).getStatusCode().value());
        for (String date : List.of("2026-02-30", "2026-10-08", "2026-1-1", "wrong")) {
            var invalid = new PeriodPreferenceController.Period(date, "2026-10-07", "2026-09-01", "2026-09-07");
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.put(() -> "alice", "p1", invalid)).getStatusCode().value());
        }
    }
}
