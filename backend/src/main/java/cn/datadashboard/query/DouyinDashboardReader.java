package cn.datadashboard.query;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.calculation.DashboardCalculator;
import cn.datadashboard.calculation.DashboardModels;
import cn.datadashboard.operations.OperationsStatus;
import java.time.LocalDate;
import java.util.List;

/** Immutable configuration history supplies verified advertiser aliases for one Douyin project. */
public final class DouyinDashboardReader {
    private final ProjectConfigurationStore store;
    private final JdbcDashboardSnapshotReader strict;
    private final LayeredDashboardReader layered;
    public DouyinDashboardReader(ProjectConfigurationStore store, JdbcDashboardSnapshotReader strict, LayeredDashboardReader layered) {
        this.store = store; this.strict = strict; this.layered = layered;
    }
    public List<String> accounts(String logicalAccount) {
        return store.lineages().stream().filter(l -> l.logicalAccount().equals(logicalAccount)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PROJECT_IDENTITY_UNAVAILABLE")).accounts();
    }
    public DashboardModels.Snapshot read(String logicalAccount) { return readPrepared(logicalAccount).snapshot(); }
    public DashboardCalculator.PreparedSnapshot readPrepared(String logicalAccount) {
        var accounts = accounts(logicalAccount);
        // The four unchanged accounts retain exactly the existing layered reader and its cache.
        return accounts.size() == 1 ? layered.readPrepared(logicalAccount)
                : DashboardCalculator.prepare(strict.readAccounts(logicalAccount, accounts));
    }
    public OperationsStatus inspect(String logicalAccount, LocalDate start, LocalDate end) {
        var accounts = accounts(logicalAccount);
        return accounts.size() == 1 ? layered.inspect(logicalAccount, start, end)
                : strict.inspectAccounts(logicalAccount, accounts, start, end);
    }
}
