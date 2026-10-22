package cn.datadashboard.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OperationsControllerTest {
    @Test void invalidProjectAndRangeNeverReadDatabase() {
        var calls = new AtomicInteger();
        var controller = new OperationsController((start, end) -> { calls.incrementAndGet(); return null; });
        assertThrows(RuntimeException.class, () -> controller.days("other", "2026-10-01", "2026-10-08"));
        for (String date : new String[]{"2026-02-30", "2026-1-01", "1899-12-31", "2201-01-01", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> controller.days("sampleproject", date, "2026-10-08"));
        }
        assertThrows(IllegalArgumentException.class, () -> controller.days("sampleproject", "2026-10-08", "2026-10-01"));
        assertThrows(IllegalArgumentException.class, () -> controller.days("sampleproject", "2026-01-01", "2027-01-02"));
        assertEquals(0, calls.get());
    }

    @Test void inclusive366DayRangeReachesReaderWithoutRewritingDates() {
        var controller = new OperationsController((start, end) -> {
            assertEquals("2026-01-01", start.toString());
            assertEquals("2027-01-01", end.toString());
            throw new ExpectedReaderCall();
        });
        assertThrows(ExpectedReaderCall.class, () -> controller.days("sampleproject", "2026-01-01", "2027-01-01"));
    }

    @Test void errorsContainOnlyFixedCodes() {
        var controller = new OperationsController((start, end) -> null);
        assertEquals(400, controller.badRequest().getStatusCode().value());
        assertEquals(404, controller.missingProject().getStatusCode().value());
        assertEquals(503, controller.failedQuery().getStatusCode().value());
        assertEquals(java.util.Map.of("code", "OPERATIONS_QUERY_UNAVAILABLE"), controller.failedQuery().getBody());
    }

    private static final class ExpectedReaderCall extends RuntimeException { }
}
