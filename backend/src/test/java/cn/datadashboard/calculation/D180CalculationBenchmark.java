package cn.datadashboard.calculation;

import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import static cn.datadashboard.calculation.DashboardModels.*;

/** Explicit synthetic-only benchmark. No JDBC, files, network, credentials or production data. */
public final class D180CalculationBenchmark {
    private static volatile Object sink;
    public static void main(String[] args) {
        Snapshot snapshot = D180CalculationEquivalenceTest.fixture(15, 700, 110);
        LocalDate end = LocalDate.of(2027, 11, 1);
        DateRange now = new DateRange(end.minusDays(29), end);
        DateRange before = new DateRange(end.minusDays(59), end.minusDays(30));
        var old = new D175ReferenceCalculator(D180CalculationEquivalenceTest.CLOCK);
        var current = new DashboardCalculator(D180CalculationEquivalenceTest.CLOCK);
        var prepared = DashboardCalculator.prepare(snapshot);
        var baseline = old.review(snapshot, "demo", now, before, 20);
        if (!baseline.equals(current.reviewPrepared(prepared, "demo", now, before, 20))) throw new AssertionError("REVIEW_MISMATCH");
        var top = old.top(snapshot, "demo", end, 20);
        if (!top.equals(current.topPrepared(prepared, "demo", end, 20))) throw new AssertionError("TOP_MISMATCH");
        Runnable reference = () -> { sink = old.review(snapshot, "demo", now, before, 20); sink = old.top(snapshot, "demo", end, 20); };
        Runnable optimized = () -> { sink = current.reviewPrepared(prepared, "demo", now, before, 20); sink = current.topPrepared(prepared, "demo", end, 20); };
        for (int i = 0; i < 4; i++) {
            optimized.run(); reference.run();
        }
        System.out.println("SYNTHETIC_FACT_ROWS=" + snapshot.facts().size());
        System.out.println("PREPARED_STATS=" + prepared.stats());
        measure("PREPARE_COLD_OR_VERSION_REBUILD", () -> sink = DashboardCalculator.prepare(snapshot));
        measure("REFERENCE_ORDER_1", reference);
        measure("PREPARED_WARM_ORDER_1", optimized);
        measure("PREPARED_WARM_ORDER_2", optimized);
        measure("REFERENCE_ORDER_2", reference);
        System.out.println("FULL_RESULT_EQUAL=true; scope=CALCULATION_ONLY; database=false; cloud=false");
    }
    private static void measure(String name, Runnable job) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long allocated = bean.getThreadAllocatedBytes(thread), started = System.nanoTime();
        for (int i = 0; i < 5; i++) job.run();
        System.out.printf(java.util.Locale.ROOT, "%s_AVERAGE_MS=%.2f; ALLOCATED_BYTES_PER_RUN=%d%n", name,
                (System.nanoTime() - started) / 5e6, (bean.getThreadAllocatedBytes(thread) - allocated) / 5);
    }
}
