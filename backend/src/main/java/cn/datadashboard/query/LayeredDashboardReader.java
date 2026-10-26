package cn.datadashboard.query;

import cn.datadashboard.calculation.DashboardCalculator;
import cn.datadashboard.calculation.DashboardModels;
import cn.datadashboard.operations.OperationsStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * D180 approved layered verification. Only dashboard queries use this adapter.
 * SQL is read-only; private disposable projections are not business evidence.
 * Unversioned raw tampering is detected by the separate strict audit, not by a warm query.
 */
public final class LayeredDashboardReader {
    private final JdbcDashboardSnapshotReader strict;
    private final DerivedDayCache disk;
    // One account only. Serial construction prevents concurrent duplicate cold builds.
    private Entry latest;
    private final java.util.Set<String> blockedInProcess = new java.util.HashSet<>();
    private Counters counters = new Counters(0, 0, 0, false);

    public LayeredDashboardReader(JdbcDashboardSnapshotReader strict, DerivedDayCache disk) {
        this.strict = Objects.requireNonNull(strict);
        this.disk = Objects.requireNonNull(disk);
    }

    public synchronized DashboardModels.Snapshot read(String accountId) {
        return readPrepared(accountId).snapshot();
    }

    public synchronized DashboardCalculator.PreparedSnapshot readPrepared(String accountId) {
        boolean forceAudit = blockedInProcess.contains(accountId);
        boolean cacheUsable = true;
        try { forceAudit |= disk.isAccountBlocked(accountId); }
        catch (DerivedDayCache.CacheException unavailable) { forceAudit = true; cacheUsable = false; }
        final boolean audit = forceAudit;
        final boolean usable = cacheUsable;
        try {
            Build build = strict.inReadOnlyTransaction(accountId, connection -> {
                List<DerivedDayCache.Key> manifest = JdbcDashboardSnapshotReader.readManifest(connection, accountId);
                if (!audit && latest != null && latest.account.equals(accountId) && latest.manifest.equals(manifest))
                    return new Build(latest, new Counters(0, 0, manifest.size(), false), true);

                List<DashboardModels.DayEvidence> days = new ArrayList<>();
                List<DashboardModels.Fact> facts = new ArrayList<>();
                Map<DerivedDayCache.Key, List<DashboardModels.Fact>> blocks = new HashMap<>();
                int rawDays = 0, diskDays = 0, memoryDays = 0;
                boolean persisted = usable;
                for (DerivedDayCache.Key key : manifest) {
                    List<DashboardModels.Fact> block = null;
                    if (!audit && latest != null && latest.account.equals(accountId)) {
                        block = latest.blocks.get(key);
                        if (block != null) memoryDays++;
                    }
                    if (block == null && !audit && usable) {
                        try {
                            Optional<List<DashboardModels.Fact>> saved = disk.load(key);
                            if (saved.isPresent()) { block = saved.get(); diskDays++; }
                        } catch (DerivedDayCache.CacheException unavailable) { persisted = false; }
                    }
                    if (block == null) {
                        block = JdbcDashboardSnapshotReader.readDay(connection, accountId, key).facts();
                        rawDays++;
                        if (usable) {
                            try { disk.save(key, block); }
                            catch (DerivedDayCache.CacheException unavailable) { persisted = false; }
                        }
                    }
                    blocks.put(key, block);
                    days.add(new DashboardModels.DayEvidence(key.date(), key.versionId(), key.contentHash(), key.sourceType()));
                    facts.addAll(block);
                }
                var snapshot = new DashboardModels.Snapshot(accountId, List.copyOf(days), List.copyOf(facts));
                var prepared = DashboardCalculator.prepare(snapshot);
                return new Build(new Entry(accountId, manifest, Map.copyOf(blocks), prepared),
                        new Counters(rawDays, diskDays, memoryDays, audit), persisted);
            });
            // Publish only after the RR transaction has ended successfully.
            latest = build.entry;
            counters = build.counters;
            if (audit && build.persisted) {
                try { disk.unblockAccount(accountId); blockedInProcess.remove(accountId); }
                catch (DerivedDayCache.CacheException unavailable) { blockedInProcess.add(accountId); }
            }
            return latest.prepared;
        } catch (RuntimeException failure) {
            block(accountId);
            throw failure;
        }
    }

    /** Full raw-byte audit, intentionally never served from the projection cache. */
    public synchronized OperationsStatus inspect(String accountId, LocalDate start, LocalDate end) {
        try { return strict.inspect(accountId, start, end); }
        catch (RuntimeException failure) { block(accountId); throw failure; }
    }

    private void block(String accountId) {
        if (latest != null && latest.account.equals(accountId)) latest = null;
        // No input values or exception text are persisted or logged.
        blockedInProcess.add(accountId);
        try { disk.blockAccount(accountId); }
        catch (DerivedDayCache.CacheException unavailable) { /* Process remains quarantined. */ }
    }

    /** Non-sensitive evidence for isolated tests/measurement, not a public metrics endpoint. */
    public synchronized Counters counters() { return counters; }
    public record Counters(int rawDaysVerified, int diskDaysLoaded, int memoryDaysReused, boolean forcedAudit) { }
    private record Entry(String account, List<DerivedDayCache.Key> manifest,
                         Map<DerivedDayCache.Key, List<DashboardModels.Fact>> blocks,
                         DashboardCalculator.PreparedSnapshot prepared) { }
    private record Build(Entry entry, Counters counters, boolean persisted) { }
}
