package cn.datadashboard.local;

import cn.datadashboard.api.DashboardController;
import cn.datadashboard.api.OperationsController;
import cn.datadashboard.api.ConfigurationController;
import cn.datadashboard.api.ProjectDirectory;
import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.api.HistoryUploadController;
import cn.datadashboard.history.HistoryUploadService;
import cn.datadashboard.ingest.persistence.JdbcHistoryFactGateway;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Map;

/** Local-only fact reads; configuration writes require an additional explicit launch option. */
public final class LocalDashboardMain {
    private LocalDashboardMain() { }

    static String trialPort(String value) {
        if (value == null || value.isBlank()) return "18080";
        if (!value.equals("18081") && !value.equals("4200")) throw new IllegalArgumentException("TRIAL_PORT_REJECTED");
        return value;
    }

    public static void main(String[] args) {
        start(args, false);
    }

    static void start(String[] args, boolean trialCloud) {
        try {
            if (args.length != 1 && args.length != 3 && args.length != 4 && args.length != 5)
                throw new IllegalArgumentException("CONFIG_REQUIRED");
            boolean configured = args.length >= 3;
            boolean configurationWrites = args.length >= 4;
            boolean historyWrites = args.length == 5;
            if ((configured && !"--configuration".equals(args[1]))
                    || (configurationWrites && !"--allow-local-config-writes".equals(args[3]))
                    || (historyWrites && !"--allow-local-history-writes".equals(args[4])))
                throw new IllegalArgumentException("CONFIG_INVALID");
            Path workspace = Path.of("").toRealPath();
            String contextPath = System.getenv("SCSJ_CONTEXT_PATH");
            if (contextPath == null) contextPath = "";
            if (!contextPath.isEmpty() && !contextPath.equals("/uranus/titania"))
                throw new IllegalArgumentException("CONTEXT_PATH_REJECTED");
            final String mountPath = contextPath;
            String publicAccess = System.getenv().getOrDefault("SCSJ_PUBLIC_ACCESS", "false");
            if (!publicAccess.equals("true") && !publicAccess.equals("false"))
                throw new IllegalArgumentException("PUBLIC_ACCESS_SETTING_REJECTED");
            String port = trialCloud ? trialPort(System.getenv("SCSJ_TRIAL_PORT")) : "18080";
            if (!java.nio.file.Files.isDirectory(workspace.resolve("backend")))
                throw new IllegalArgumentException("PROJECT_ROOT_REQUIRED");
            var plan = LocalFactImportMain.readDashboardPlan(Path.of(args[0]), workspace);
            var dataSource = new DriverManagerDataSource(LocalFactVerifyMain.jdbcUrl(plan),
                    plan.credentials().username(), plan.credentials().password());
            var reader = new JdbcDashboardSnapshotReader(dataSource);
            var layered = new cn.datadashboard.query.LayeredDashboardReader(reader,
                    new cn.datadashboard.query.DerivedDayCache(workspace.resolve(".local/dashboard-derived-v1")));
            var store = configured ? new ProjectConfigurationStore(workspace, Path.of(args[2])) : null;
            if (store != null) store.snapshot(); // No implicit initialization or fallback on damaged config.
            var directory = store == null ? ProjectDirectory.single(plan.expectedAccountId())
                    : new ProjectDirectory(() -> store.snapshot().document().projects().stream()
                    .map(p -> new ProjectDirectory.Binding(p.id(), p.name(), store.lineage(p.id()).logicalAccount(), p.enabled() && !p.deleted(), p.category())).toList());
            var cumulative = store == null ? null : new cn.datadashboard.query.DouyinDashboardReader(store, reader, layered);
            var controller = cumulative == null ? new DashboardController(directory, layered::read, layered::readPrepared)
                    : new DashboardController(directory, cumulative::read, cumulative::readPrepared);
            // This wrapper always calls the strict reader and quarantines a known-bad derived account.
            var operations = new OperationsController(directory, cumulative == null ? layered::inspect : cumulative::inspect);
            var operationsFiles = trialCloud ? new cn.datadashboard.files.OperationsFileStore(workspace.resolve(".local/operations-files")) : null;
            var trialGuard = trialCloud ? new cn.datadashboard.security.TrialRequestGuard(
                    java.util.Objects.requireNonNull(System.getenv("SCSJ_PUBLIC_ORIGIN"))) : null;
            if (trialCloud && !java.nio.file.Files.isRegularFile(workspace.resolve("frontend/dist/index.html")))
                throw new IllegalArgumentException("STATIC_BUILD_REQUIRED");
            var app = new SpringApplication(trialCloud ? TrialWebConfiguration.class : LocalWebConfiguration.class);
            app.setLogStartupInfo(false);
            app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
            app.setDefaultProperties(Map.of("logging.level.root", "OFF",
                    "server.servlet.context-path", mountPath,
                    "server.servlet.session.cookie.name", mountPath.isEmpty() ? "JSESSIONID" : "SCSJ_TITANIA_SESSION",
                    "scsj.auth.remember-cookie-name", mountPath.isEmpty() ? "remember-me" : "scsj-titania-remember-me"));
            app.addInitializers(context -> {
                if (trialCloud) context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("trial-settings", Map.of(
                        "spring.web.resources.static-locations", workspace.resolve("frontend/dist").toUri().toString(),
                        "scsj.auth.state-directory", workspace.resolve(".local/user-state").toString(),
                        "scsj.auth.public-access", publicAccess,
                        "server.servlet.session.cookie.secure", "true", "server.servlet.session.cookie.http-only", "true",
                        "server.servlet.session.cookie.same-site", "lax", "server.servlet.session.timeout", "30m",
                        "server.forward-headers-strategy", "none")));
                // Both runners stay on loopback. The trial runner is reached through its HTTPS proxy.
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-only-guard", Map.of(
                        "server.address", "127.0.0.1", "server.port", port,
                        "spring.flyway.enabled", "false", "spring.sql.init.mode", "never",
                        "logging.level.root", "OFF", "spring.main.banner-mode", "off",
                        "server.error.include-message", "never", "server.error.include-stacktrace", "never",
                        "server.tomcat.accesslog.enabled", "false")));
                context.getBeanFactory().registerSingleton("dataSource", dataSource);
                context.getBeanFactory().registerSingleton("dashboardController", controller);
                context.getBeanFactory().registerSingleton("operationsController", operations);
                context.getBeanFactory().registerSingleton("collectionStatusController", new cn.datadashboard.api.CollectionStatusController(
                        new cn.datadashboard.operations.CollectionStatusReader(workspace, directory)));
                if (trialCloud) context.getBeanFactory().registerSingleton("periodPreferenceController",
                        new cn.datadashboard.security.PeriodPreferenceController(workspace.resolve(".local/user-state/preferences"), directory));
                if (trialCloud) context.getBeanFactory().registerSingleton("reviewSnapshotController",
                        new cn.datadashboard.security.ReviewSnapshotController(workspace.resolve(".local/user-state/review-snapshots"), directory, controller));
                if (trialCloud) context.getBeanFactory().registerSingleton("operationsFilesController",
                        new cn.datadashboard.api.OperationsFilesController(
                                operationsFiles, trialGuard, historyWrites));
                if (trialCloud) context.getBeanFactory().registerSingleton("databaseBackupController",
                        new cn.datadashboard.api.DatabaseBackupController(
                                new cn.datadashboard.backup.WeeklyDatabaseBackupStore(
                                        workspace.resolve(".local/database-period-weekly")), trialGuard)
                                .withCustom(new cn.datadashboard.backup.WeeklyDatabaseBackupStore(workspace.resolve(".local/database-period-custom")),
                                        new cn.datadashboard.backup.PeriodBackupService(workspace,
                                                System.getenv().getOrDefault("SCSJ_PYTHON_EXECUTABLE", "python"))));
                if (store != null) {
                    var resolver = cn.datadashboard.configuration.AnchorResolver.local(workspace);
                    context.getBeanFactory().registerSingleton("configurationController", trialCloud
                            ? new ConfigurationController(store, configurationWrites, resolver, trialGuard).withUnusedAccountCheck(account -> reader.read(account).days().isEmpty())
                            : new ConfigurationController(store, configurationWrites, resolver).withUnusedAccountCheck(account -> reader.read(account).days().isEmpty()));
                }
                if (store != null) {
                    String configuredPython = System.getenv("SCSJ_PYTHON_EXECUTABLE");
                    Path python = Path.of(configuredPython == null || configuredPython.isBlank() ? "python" : configuredPython);
                    var exports = new cn.datadashboard.export.CollectedExportService(id -> store.snapshot().document().projects().stream()
                            .filter(p -> p.id().equals(id) && p.enabled() && !p.deleted()).findFirst()
                            .map(p -> new cn.datadashboard.export.CollectedExportService.Project(p.id(), p.name(), store.lineage(p.id()).logicalAccount()))
                            .orElseThrow(() -> new cn.datadashboard.export.CollectedExportService.Failure("EXPORT_PROJECT_UNAVAILABLE")),
                            (account, start, end) -> new cn.datadashboard.export.JdbcCollectedExportReader(dataSource).readAccounts(cumulative.accounts(account), start, end),
                            cn.datadashboard.export.CollectedExportService.pythonWorkbook(workspace, python.toString()));
                    context.getBeanFactory().registerSingleton("collectedExportController", trialCloud
                            ? new cn.datadashboard.api.CollectedExportController(exports, trialGuard)
                            : new cn.datadashboard.api.CollectedExportController(exports));
                    var history = new HistoryUploadService(workspace, store, new JdbcHistoryFactGateway(dataSource), python, historyWrites);
                    context.getBeanFactory().registerSingleton("historyUploadController", trialCloud
                            ? new HistoryUploadController(history, trialGuard) : new HistoryUploadController(history));
                }
            });
            app.run();
            System.out.println(trialCloud ? "Trial API ready behind HTTPS proxy; collection disabled."
                    : historyWrites
                    ? "Local dashboard API ready (explicit history confirmation enabled; collection disabled): http://127.0.0.1:18080"
                    : configurationWrites
                    ? "Local dashboard API ready (local configuration writes enabled; collection disabled): http://127.0.0.1:18080"
                    : "Local read-only dashboard API ready: http://127.0.0.1:18080");
        } catch (Exception failure) {
            System.err.println(trialCloud ? "TRIAL_DASHBOARD_START_REJECTED" : "LOCAL_DASHBOARD_START_REJECTED");
            System.exit(2);
        }
    }

    // Deliberately no component scan: only explicitly assembled, guarded endpoints are exposed.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
            org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class
    })
    static class LocalWebConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @org.springframework.context.annotation.Import({cn.datadashboard.security.TrialSecurityConfiguration.class,
            cn.datadashboard.security.TrialSessionController.class})
    static class TrialWebConfiguration { }
}
