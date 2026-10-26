package cn.datadashboard.backup;

import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.TimeUnit;

/** One bounded custom job per application; the worker also locks across processes/schedulers. */
public final class PeriodBackupService {
    public interface Runner { int run(String start, String end) throws Exception; }
    private final Runner runner;
    private final Clock clock;
    private boolean running;
    private String failure;
    private long lastStarted;
    public record Job(boolean running, String errorCode) { }

    public PeriodBackupService(Path workspace, String python) {
        this((start, end) -> {
            var process = new ProcessBuilder(python, "-B", workspace.resolve("tools/maintenance/period_database_backup_titania.py").toString(),
                    "custom", "--start", start, "--end", end).directory(workspace.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!process.waitFor(26, TimeUnit.MINUTES)) {
                    process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
                    return 2;
                }
                return process.exitValue();
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }, Clock.system(ZoneId.of("Asia/Shanghai")));
    }
    public PeriodBackupService(Runner runner, Clock clock) { this.runner = runner; this.clock = clock; }
    public synchronized Job status() { return new Job(running, failure); }
    public synchronized Job start(String start, String end) {
        try {
            if (start == null || end == null || !start.matches("\\d{4}-\\d{2}-\\d{2}") || !end.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
            LocalDate first = LocalDate.parse(start), last = LocalDate.parse(end);
            if (first.isBefore(LocalDate.of(2000,1,1)) || first.isAfter(last) || !last.isBefore(LocalDate.now(clock))) throw new IllegalArgumentException();
        } catch (DateTimeException invalid) { throw new IllegalArgumentException(); }
        if (running || lastStarted != 0 && clock.millis() - lastStarted < 60_000) throw new WeeklyDatabaseBackupStore.Conflict("BACKUP_BUSY_OR_COOLDOWN");
        running = true; failure = null; lastStarted = clock.millis();
        Thread.ofPlatform().daemon().name("custom-period-backup").start(() -> {
            String error = null;
            try { if (runner.run(start, end) != 0) error = "CUSTOM_BACKUP_FAILED_KEEP_PREVIOUS"; }
            catch (Exception ignored) { error = "CUSTOM_BACKUP_FAILED_KEEP_PREVIOUS"; }
            synchronized (this) { failure = error; running = false; }
        });
        return status();
    }
}
