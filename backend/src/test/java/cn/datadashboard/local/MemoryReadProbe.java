package cn.datadashboard.local;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import cn.datadashboard.calculation.DashboardCalculator;
import cn.datadashboard.calculation.DashboardModels;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import tools.jackson.databind.json.JsonMapper;

/** Explicit read-only real-data memory probe. Never prints business values or secrets. */
public final class MemoryReadProbe {
    public static void main(String[] args) throws Exception {
        var root = Path.of("").toRealPath();
        var plan = LocalFactImportMain.readPlan(Path.of(args[0]), root);
        var store = new ProjectConfigurationStore(root, Path.of(args[1]));
        var source = new DriverManagerDataSource(LocalFactVerifyMain.jdbcUrl(plan),
                plan.credentials().username(), plan.credentials().password());
        var reader = new JdbcDashboardSnapshotReader(source);
        var projects = store.snapshot().document().projects().stream().filter(p -> p.enabled() && !p.deleted()).toList();
        var tasks = new ArrayList<Callable<String>>();
        int repeats = Integer.parseInt(args[2]);
        for (int i = 0; i < repeats; i++) for (var p : projects) tasks.add(() -> {
            var snapshot = reader.read(p.advertiserId());
            var date = snapshot.days().stream().map(DashboardModels.DayEvidence::date).max(java.util.Comparator.naturalOrder()).orElseThrow();
            var calculator = new DashboardCalculator(java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-15T00:00:00Z"), java.time.ZoneOffset.UTC));
            var json = JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
            var sha = MessageDigest.getInstance("SHA-256");
            sha.update(json.writeValueAsBytes(calculator.top(snapshot, p.id(), date, 10)));
            sha.update(json.writeValueAsBytes(calculator.review(snapshot, p.id(),
                new DashboardModels.DateRange(date.minusDays(6), date),
                new DashboardModels.DateRange(date.minusDays(13), date.minusDays(7)), 10)));
            return "rows=" + snapshot.facts().size() + " hash=" + HexFormat.of().formatHex(sha.digest());
        });
        try (var pool = Executors.newFixedThreadPool(Integer.parseInt(args[3]))) {
            for (var result : pool.invokeAll(tasks)) System.out.println(result.get());
        }
        System.out.println("READ_ONLY_MEMORY_PROBE_PASSED heapMax=" + Runtime.getRuntime().maxMemory());
    }
}
