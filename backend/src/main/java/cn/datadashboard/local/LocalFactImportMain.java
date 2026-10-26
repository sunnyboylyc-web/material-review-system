package cn.datadashboard.local;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.datadashboard.ingest.persistence.FactImportReceipt;
import cn.datadashboard.ingest.persistence.FactImportStatus;
import cn.datadashboard.ingest.persistence.FactImportStore;
import cn.datadashboard.ingest.persistence.FactStoreResult;
import cn.datadashboard.ingest.persistence.ManualHistoryImportCommand;
import cn.datadashboard.ingest.persistence.ManualHistoryImportService;
import cn.datadashboard.ingest.persistence.PreparedFactBatch;
import cn.datadashboard.ingest.persistence.VersionedFactImportCommand;
import cn.datadashboard.ingest.persistence.VersionedFactImportService;
import cn.datadashboard.ingest.validation.FactInputValidationRequest;
import cn.datadashboard.ingest.validation.FactInputValidator;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Local-only, explicitly configured import runner; it never starts the web application. */
public final class LocalFactImportMain {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> OWN_TABLES = Set.of("scsj_fact_input_version",
            "scsj_material_day_fact", "scsj_fact_day_state", "scsj_flyway_schema_history");

    private LocalFactImportMain() { }

    public static void main(String[] args) {
        silenceFrameworkLogs();
        int exit;
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("CONFIG_ARGUMENT_REQUIRED");
            }
            exit = execute(readPlan(Path.of(args[0]), locateWorkspace()));
        } catch (Exception exception) {
            // Never print driver errors, parsed values, credentials, account IDs or raw input rows.
            System.err.println("Local fact import stopped: configuration, database or receipt preflight failed.");
            exit = 2;
        }
        System.exit(exit);
    }

    private static void silenceFrameworkLogs() {
        // Driver/SQL exception translation can include raw duplicate keys even at DEBUG.
        // This standalone runner reports only the explicit sanitized receipt and exit code.
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext logging) {
            logging.reset();
            logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        }
        java.util.logging.LogManager.getLogManager().reset();
    }

    static int execute(ImportPlan plan) throws Exception {
        Path runs = plan.localRoot().resolve("import-runs");
        if (!Files.exists(runs)) {
            Files.createDirectory(runs);
        }
        runs = runs.toRealPath();
        requireWithin(runs, plan.localRoot());
        Path receiptDirectory = runs.resolve(
                plan.runId() + "-" + UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectory(receiptDirectory);
        requireWithin(receiptDirectory.toRealPath(), plan.localRoot());
        Path receiptPath = receiptDirectory.resolve("receipt.json");
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schemaVersion", "local-fact-import-receipt-1.0.0");
        receipt.put("runId", plan.runId());
        receipt.put("startedAt", Instant.now().toString());
        receipt.put("requestedDays", plan.days().size());
        receipt.put("status", "RUNNING");
        ArrayNode units = receipt.putArray("days");
        writeReceipt(receiptPath, receipt);

        try {
            DataSource dataSource = initializeSchema(plan);
            try (AnnotationConfigApplicationContext context = createContext(dataSource, plan.expectedAccountId(), plan.storageAccountId())) {
                ScopedStore guard = context.getBean(ScopedStore.class);
                FactInputValidator validator = context.getBean(FactInputValidator.class);
                VersionedFactImportService automatic = context.getBean(VersionedFactImportService.class);
                ManualHistoryImportService manual = context.getBean(ManualHistoryImportService.class);
                boolean complete = true;
                for (ImportDay day : plan.days()) {
                    ObjectNode result = units.addObject();
                    result.put("date", day.date().toString());
                    result.put("sourceType", day.automatic() ? "MCP_AUTO" : "MANUAL_HISTORY");
                    try {
                        guard.expect(day.date());
                        FactImportReceipt imported;
                        if (day.automatic()) {
                            // Resolve again immediately before read; evidence paths may never escape .local.
                            validateAutomaticPaths(day.inputPath(), plan.localRoot());
                            var request = new FactInputValidationRequest(plan.runId(), day.inputPath(),
                                    plan.expectedAccountId(), day.date());
                            var validation = validator.validate(request);
                            imported = automatic.importValidated(new VersionedFactImportCommand(request, validation));
                        } else {
                            JsonNode evidence = verifyManualIdentity(day, plan.localRoot(), plan.expectedAccountId());
                            imported = manual.importReady(new ManualHistoryImportCommand(
                                    text(evidence, "runId"), day.inputPath(), plan.localRoot()));
                        }
                        result.put("status", imported.status().name());
                        result.put("rowCount", imported.rowCount());
                        if (imported.inputVersionId() != null) {
                            result.put("inputVersionId", imported.inputVersionId());
                        }
                        if (imported.disposition() != null) {
                            result.put("disposition", imported.disposition().name());
                        }
                        if (imported.errorCode() != null) {
                            result.put("errorCode", imported.errorCode().name());
                        }
                        boolean published = (imported.status() == FactImportStatus.IMPORTED_CURRENT
                                || imported.status() == FactImportStatus.IDEMPOTENT)
                                && imported.disposition() != null
                                && "CURRENT".equals(imported.disposition().name());
                        result.put("publishedCurrent", published);
                        complete &= published;
                    } catch (Exception exception) {
                        result.put("status", "REJECTED");
                        result.put("errorCode", "LOCAL_UNIT_PREFLIGHT_OR_EXECUTION_FAILED");
                        result.put("rowCount", 0);
                        result.put("publishedCurrent", false);
                        complete = false;
                    }
                    writeReceipt(receiptPath, receipt);
                }
                receipt.put("status", complete ? "COMPLETE" : "PARTIAL_OR_REJECTED");
                receipt.put("finishedAt", Instant.now().toString());
                writeReceipt(receiptPath, receipt);
                System.out.println("Local fact import receipt: " + receiptPath);
                return complete ? 0 : 1;
            }
        } catch (Exception exception) {
            receipt.put("status", "STOPPED");
            receipt.put("errorCode", "LOCAL_DATABASE_OR_RECEIPT_FAILED");
            receipt.put("finishedAt", Instant.now().toString());
            writeReceipt(receiptPath, receipt);
            System.err.println("Local fact import stopped; see the sanitized receipt: " + receiptPath);
            return 2;
        }
    }

    static ImportPlan readPlan(Path configPath, Path workspace) throws Exception {
        return readPlan(configPath, workspace, true);
    }

    static ImportPlan readDashboardPlan(Path configPath, Path workspace) throws Exception {
        return readPlan(configPath, workspace, false);
    }

    private static ImportPlan readPlan(Path configPath, Path workspace, boolean requireImportDays) throws Exception {
        Path root = workspace.toRealPath();
        Path localRoot = root.resolve(".local").toRealPath();
        requireWithin(localRoot, root);
        Path config = fileWithin(configPath, localRoot);
        JsonNode node = object(config);
        var fields = new HashSet<>(Set.of("workspaceRoot", "credentialsPath", "schemaName", "runId",
                "expectedAccountId", "autoDays", "manualDays"));
        if (node.has("storageAccountId")) fields.add("storageAccountId");
        requireKeys(node, fields);
        if (!Path.of(text(node, "workspaceRoot")).toRealPath().equals(root)) {
            throw new IllegalArgumentException("WORKSPACE_MISMATCH");
        }
        String schema = text(node, "schemaName");
        if (!(schema.equals("material_dashboard") || schema.matches("SCSJ_LOCAL_[A-Z0-9_]+")) || schema.length() > 64) {
            throw new IllegalArgumentException("SCHEMA_NAME_INVALID");
        }
        String run = text(node, "runId");
        if (!run.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")) {
            throw new IllegalArgumentException("RUN_ID_INVALID");
        }
        String account = text(node, "expectedAccountId");
        if (!account.matches("[0-9]{1,64}")) {
            throw new IllegalArgumentException("ACCOUNT_ID_INVALID");
        }
        String storage = node.has("storageAccountId") ? text(node, "storageAccountId") : account;
        if (!storage.matches("[0-9]{1,64}")) throw new IllegalArgumentException("STORAGE_ID_INVALID");
        JsonNode credentials = object(fileWithin(Path.of(text(node, "credentialsPath")), localRoot));
        requireKeys(credentials, Set.of("username", "password", "port"));
        JsonNode password = credentials.get("password");
        JsonNode portNode = credentials.get("port");
        if (password == null || !password.isString() || portNode == null || !portNode.isIntegralNumber()
                || !portNode.canConvertToInt() || portNode.intValue() < 1024 || portNode.intValue() > 65535) {
            throw new IllegalArgumentException("CREDENTIALS_INVALID");
        }
        Credentials connection = new Credentials(text(credentials, "username"), password.stringValue(), portNode.intValue());
        List<ImportDay> days = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        for (String kind : List.of("autoDays", "manualDays")) {
            JsonNode entries = node.get(kind);
            if (entries == null || !entries.isArray()) {
                throw new IllegalArgumentException("DAYS_ARRAY_REQUIRED");
            }
            boolean automatic = kind.equals("autoDays");
            for (JsonNode entry : entries) {
                String pathField = automatic ? "inputDirectory" : "dayEvidencePath";
                requireKeys(entry, Set.of("date", pathField));
                LocalDate date = LocalDate.parse(text(entry, "date"));
                if (!seen.add(date)) {
                    throw new IllegalArgumentException("DUPLICATE_OR_OVERLAPPING_DAY");
                }
                Path path = Path.of(text(entry, pathField)).toRealPath();
                requireWithin(path, localRoot);
                ImportDay day = new ImportDay(date, automatic, path);
                if (automatic) {
                    validateAutomaticPaths(path, localRoot);
                } else {
                    verifyManualIdentity(day, localRoot, account);
                }
                days.add(day);
            }
        }
        if (requireImportDays && days.isEmpty()) {
            throw new IllegalArgumentException("NO_IMPORT_DAYS");
        }
        return new ImportPlan(root, localRoot, schema, run, account, connection, List.copyOf(days), storage);
    }

    private static DataSource initializeSchema(ImportPlan plan) {
        Credentials credentials = plan.credentials();
        JdbcTemplate admin = new JdbcTemplate(dataSource(credentials, ""));
        boolean exists = Boolean.TRUE.equals(admin.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM information_schema.SCHEMATA WHERE LOWER(SCHEMA_NAME) = LOWER(?))
                """, Boolean.class, plan.schemaName()));
        if (!exists) {
            admin.execute("CREATE DATABASE `" + plan.schemaName()
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } else {
            Set<String> actual = new HashSet<>(admin.queryForList("""
                    SELECT LOWER(TABLE_NAME) FROM information_schema.TABLES
                    WHERE LOWER(TABLE_SCHEMA) = LOWER(?) AND TABLE_TYPE = 'BASE TABLE'
                    """, String.class, plan.schemaName()));
            if (!(plan.schemaName().equals("material_dashboard") ? actual.containsAll(OWN_TABLES) : actual.equals(OWN_TABLES))) {
                throw new IllegalStateException("EXISTING_SCHEMA_IDENTITY_REJECTED");
            }
        }
        DataSource dataSource = dataSource(credentials, plan.schemaName());
        Flyway flyway = Flyway.configure().table("scsj_flyway_schema_history").dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(true).load();
        if (exists) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer knownMigration = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM scsj_flyway_schema_history
                    WHERE version = '1' AND script = 'V1__create_versioned_material_facts.sql' AND success = 1
                    """, Integer.class);
            if (!Integer.valueOf(1).equals(knownMigration) || !flyway.validateWithResult().validationSuccessful) {
                throw new IllegalStateException("EXISTING_SCHEMA_MIGRATION_REJECTED");
            }
        }
        flyway.migrate();
        return dataSource;
    }

    static AnnotationConfigApplicationContext createContext(DataSource source, String account) {
        return createContext(source, account, account);
    }

    static AnnotationConfigApplicationContext createContext(DataSource source, String account, String storage) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(source));
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean("scopedImportStore", ScopedStore.class,
                () -> new ScopedStore(context.getBean("jdbcFactImportStore", FactImportStore.class), account, storage),
                definition -> definition.setPrimary(true));
        context.register(TransactionConfiguration.class);
        context.scan("cn.datadashboard.ingest");
        context.refresh();
        return context;
    }

    private static DriverManagerDataSource dataSource(Credentials credentials, String schema) {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + credentials.port() + "/" + schema
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&connectTimeout=5000&socketTimeout=30000&allowMultiQueries=false",
                credentials.username(), credentials.password());
    }

    static JsonNode verifyManualIdentity(ImportDay day, Path localRoot, String account) throws Exception {
        JsonNode evidence = object(fileWithin(day.inputPath(), localRoot));
        if (!account.equals(text(evidence, "accountId"))
                || !day.date().equals(LocalDate.parse(text(evidence, "statDate")))) {
            throw new IllegalArgumentException("MANUAL_EXPECTED_IDENTITY_MISMATCH");
        }
        return evidence;
    }

    private static void validateAutomaticPaths(Path directory, Path localRoot) throws IOException {
        requireWithin(directory.toRealPath(), localRoot);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("AUTOMATIC_DIRECTORY_REQUIRED");
        }
        fileWithin(directory.resolve("manifest.json"), localRoot);
        fileWithin(directory.resolve("material_day.json"), localRoot);
    }

    private static Path locateWorkspace() throws IOException {
        Path current = Path.of("").toRealPath();
        Path workspace = Files.isDirectory(current.resolve("backend")) ? current : current.getParent();
        if (workspace == null || !Files.isRegularFile(workspace.resolve("START_HERE.md"))
                || !Files.isRegularFile(workspace.resolve("backend/pom.xml"))) {
            throw new IllegalArgumentException("PROJECT_WORKING_DIRECTORY_REQUIRED");
        }
        return workspace;
    }

    private static Path fileWithin(Path file, Path root) throws IOException {
        Path real = file.toRealPath();
        requireWithin(real, root);
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("REGULAR_FILE_REQUIRED");
        }
        return real;
    }

    private static void requireWithin(Path path, Path root) {
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("PATH_OUTSIDE_LOCAL_ROOT");
        }
    }

    private static JsonNode object(Path path) throws IOException {
        JsonNode node = JSON.readTree(Files.readAllBytes(path));
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("JSON_OBJECT_REQUIRED");
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException("CONFIG_TEXT_REQUIRED");
        }
        return value.stringValue();
    }

    private static void requireKeys(JsonNode node, Set<String> expected) {
        if (!node.isObject() || !node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(expected)) {
            throw new IllegalArgumentException("CONFIG_FIELDS_MISMATCH");
        }
    }

    private static void writeReceipt(Path path, ObjectNode receipt) throws IOException {
        Path temporary = path.resolveSibling("receipt.pending.json");
        Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    record Credentials(String username, String password, int port) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    record ImportDay(LocalDate date, boolean automatic, Path inputPath) { }
    record ImportPlan(Path workspaceRoot, Path localRoot, String schemaName, String runId,
                      String expectedAccountId, Credentials credentials, List<ImportDay> days, String storageAccountId) {
        ImportPlan(Path workspaceRoot, Path localRoot, String schemaName, String runId, String expectedAccountId, Credentials credentials, List<ImportDay> days) {
            this(workspaceRoot, localRoot, schemaName, runId, expectedAccountId, credentials, days, expectedAccountId);
        }
        @Override public String toString() { return "ImportPlan[redacted]"; }
    }

    static PreparedFactBatch storageBatch(PreparedFactBatch b, String storage) {
        return new PreparedFactBatch(b.runId(), b.projectRef(), storage, b.statDate(), b.sourceType(),
                b.dataVersion(), b.contentHash(), b.schemaVersion(), b.catalogVersion(), b.manifestEvidence(), b.materialEvidence(), b.rows());
    }

    static final class ScopedStore implements FactImportStore {
        private final FactImportStore delegate;
        private final String account;
        private final String storage;
        private LocalDate expectedDate;

        ScopedStore(FactImportStore delegate, String account) {
            this(delegate, account, account);
        }

        ScopedStore(FactImportStore delegate, String account, String storage) {
            this.delegate = delegate;
            this.account = account;
            this.storage = storage;
        }

        void expect(LocalDate date) { expectedDate = date; }

        @Override public FactStoreResult importAtomically(PreparedFactBatch batch) {
            if (!account.equals(batch.accountId()) || !batch.statDate().equals(expectedDate)) {
                throw new IllegalArgumentException("TRANSACTION_SCOPE_IDENTITY_MISMATCH");
            }
            return delegate.importAtomically(storageBatch(batch, storage));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration { }
}
