package cn.datadashboard.backup;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class PeriodBackupServiceTest {
    Clock clock = Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    @Test void rejectsInvalidRangesWithoutStartingProcess() {
        var service=new PeriodBackupService((a,b)->{ fail("process must not run"); return 0; },clock);
        assertThrows(IllegalArgumentException.class,()->service.start("2026-10-22","2026-10-22"));
        assertThrows(IllegalArgumentException.class,()->service.start("2026-02-30","2026-03-01"));
        assertThrows(IllegalArgumentException.class,()->service.start("2026-10-20","2026-10-19"));
        assertThrows(IllegalArgumentException.class,()->service.start("2026-01-01; exit","2026-10-19"));
        assertFalse(service.status().running());
    }
    @Test void acceptsHistoricalPeriodButRejectsConcurrentJobs() throws Exception {
        var entered=new CountDownLatch(1); var finish=new CountDownLatch(1);
        var service=new PeriodBackupService((a,b)->{ assertEquals("2024-03-31",a); assertEquals("2026-10-21",b); entered.countDown(); finish.await(5,TimeUnit.SECONDS); return 0; },clock);
        assertTrue(service.start("2024-03-31","2026-10-21").running());
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertThrows(WeeklyDatabaseBackupStore.Conflict.class,()->service.start("2026-10-01","2026-10-07"));
        finish.countDown();
    }
}
