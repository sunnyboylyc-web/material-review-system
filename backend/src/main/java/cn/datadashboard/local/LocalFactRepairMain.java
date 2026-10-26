package cn.datadashboard.local;

import cn.datadashboard.ingest.persistence.FactStoreResult;
import cn.datadashboard.ingest.persistence.FactVersionDisposition;
import cn.datadashboard.ingest.persistence.PreparedFactBatch;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit one-day local repair. It never creates schemas, migrates, collects, deletes or retries. */
public final class LocalFactRepairMain {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> TABLES = Set.of("scsj_fact_input_version", "scsj_material_day_fact",
            "scsj_fact_day_state", "scsj_flyway_schema_history");

    private LocalFactRepairMain() { }

    public static void main(String[] args) {
        silenceLogs();
        int exit;
        try {
            Path current = Path.of("").toRealPath();
            Path root = Files.isDirectory(current.resolve("backend")) ? current : current.getParent();
            require(root != null && Files.isRegularFile(root.resolve("START_HERE.md"))
                    && Files.isRegularFile(root.resolve("backend/pom.xml")), "WORKSPACE_REJECTED");
            exit = run(args, root, System.out, System.err);
        } catch (Exception ignored) {
            System.err.println("Local single-day repair stopped: workspace or receipt preflight failed.");
            exit = 2;
        }
        System.exit(exit);
    }

    static int run(String[] args, Path workspace, PrintStream out, PrintStream err) {
        try {
            Result result = execute(args, workspace, Hooks.NONE);
            out.println("Local single-day repair receipt: " + result.receiptPath());
            return switch (result.status()) {
                case CURRENT_VERIFIED -> 0;
                case REJECTED -> 1;
                case STORED_NOT_CURRENT -> 3;
                case COMMITTED_UNVERIFIED -> 4;
                case EXECUTION_UNCERTAIN -> 5;
            };
        } catch (Exception ignored) {
            err.println("Local single-day repair stopped: arguments, local paths or receipt preflight failed.");
            return 2;
        }
    }

    static Result execute(String[] args, Path workspace, Hooks hooks) throws Exception {
        silenceLogs();
        Arguments arguments = arguments(args);
        Path root = workspace.toRealPath();
        Path local = root.resolve(".local").toRealPath();
        require(local.equals(root.resolve(".local")) && Files.isDirectory(local), "LOCAL_ROOT_REJECTED");
        Path directory = newRun(local);
        Path output = directory.resolve("receipt.json");
        ObjectNode receipt = initialReceipt(arguments);
        writeReceipt(output, receipt, local);
        boolean transactionAttempted = false;
        boolean transactionReturned = false;
        Status status;
        try {
            Path config = fileWithin(arguments.config(), local);
            Path preflight = fileWithin(arguments.preflight(), local);
            byte[] suppliedBytes = Files.readAllBytes(preflight);
            require(LocalFactVerifyMain.sha(suppliedBytes).equals(arguments.preflightSha()), "PREFLIGHT_BYTES_CHANGED");
            JsonNode ready = object(suppliedBytes);
            require("local-source-preflight-1.0.0".equals(text(ready, "schemaVersion"))
                    && "SOURCE_READY".equals(text(ready, "status"))
                    && arguments.date().toString().equals(text(ready, "statDate")), "PREFLIGHT_NOT_READY_FOR_DAY");
            receipt.set("preflightEvidence", evidence(preflight, suppliedBytes));
            verifyEvidence(ready.get("evidence"), local);
            var fresh = LocalFactPreflightMain.execute(config, arguments.date().toString(), root);
            require(fresh.status() == LocalFactPreflightMain.Status.SOURCE_READY, "FRESH_SOURCE_PREFLIGHT_REJECTED");
            JsonNode freshReceipt = object(Files.readAllBytes(fresh.receiptPath()));
            for (String key : List.of("sourceType", "contentHash", "rowCount", "evidence")) {
                require(ready.get(key) != null && ready.get(key).equals(freshReceipt.get(key)), "PREFLIGHT_EVIDENCE_CHANGED");
            }
            PreparedFactBatch prepared = LocalFactPreflightMain.prepareSelected(config, arguments.date().toString(), root);
            require(prepared.contentHash().equals(text(ready, "contentHash"))
                    && ready.get("rowCount").isIntegralNumber()
                    && prepared.rows().size() == ready.get("rowCount").longValue(), "PREPARED_SOURCE_CHANGED");
            PreparedFactBatch batch = withRun(prepared, directory.getFileName().toString());
            receipt.put("sourceType", batch.sourceType().name());
            receipt.put("contentHash", batch.contentHash());
            receipt.put("rowCount", batch.rows().size());
            receipt.set("sourceEvidence", ready.get("evidence").deepCopy());
            verifyEvidence(ready.get("evidence"), local);
            verifyFile(preflight, suppliedBytes.length, arguments.preflightSha(), local);
            LocalFactImportMain.ImportPlan plan = executionPlan(config, root, local, batch);
            receipt.putObject("target").put("schema", plan.schemaName()).put("port", plan.credentials().port())
                    .put("host", "127.0.0.1");
            DataSource source = new DriverManagerDataSource(LocalFactVerifyMain.jdbcUrl(plan),
                    plan.credentials().username(), plan.credentials().password());
            verifyTarget(source, plan, receipt);
            writeReceipt(output, receipt, local);

            FactStoreResult stored;
            try (var context = LocalFactImportMain.createContext(source, batch.accountId())) {
                var guarded = context.getBean(LocalFactImportMain.ScopedStore.class);
                guarded.expect(batch.statDate());
                var template = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
                template.setTimeout(60);
                transactionAttempted = true;
                stored = template.execute(transaction -> {
                    var jdbc = context.getBean(JdbcTemplate.class);
                    Before before = lockAndCheck(jdbc, batch, arguments.expectedCurrent());
                    ObjectNode beforeNode = receipt.putObject("beforeState");
                    nullable(beforeNode, "currentVersionId", before.current());
                    nullable(beforeNode, "previousVersionId", before.previous());
                    if (before.hash() == null) beforeNode.putNull("currentHash"); else beforeNode.put("currentHash", before.hash());
                    // Persist exact before pointers before any version write; failure rejects this transaction.
                    try {
                        verifyEvidence(ready.get("evidence"), local);
                        verifyFile(preflight, suppliedBytes.length, arguments.preflightSha(), local);
                        writeReceipt(output, receipt, local);
                    } catch (Exception ignored) {
                        throw new Rejection("SOURCE_OR_RECEIPT_CHANGED_BEFORE_WRITE");
                    }
                    FactStoreResult result = guarded.importAtomically(batch);
                    hooks.beforeTransactionCompletion();
                    return result;
                });
                transactionReturned = true;
            }
            require(stored != null, "TRANSACTION_RESULT_MISSING");
            receipt.put("transactionOutcome", stored.outcome().name());
            receipt.put("inputVersionId", stored.inputVersionId());
            receipt.put("disposition", stored.disposition().name());
            receipt.put("transactionReturned", true);
            writeReceipt(output, receipt, local);

            if (stored.disposition() != FactVersionDisposition.CURRENT) {
                verifyEvidence(ready.get("evidence"), local);
                verifyFile(preflight, suppliedBytes.length, arguments.preflightSha(), local);
                receipt.put("independentVerification", "NOT_CURRENT_NOT_ATTEMPTED");
                status = Status.STORED_NOT_CURRENT;
            } else {
                hooks.beforeIndependentVerification();
                try (var connection = LocalFactVerifyMain.openReadOnly(plan)) {
                    var actual = LocalFactVerifyMain.readDay(connection, batch);
                    LocalFactVerifyMain.compare(batch, actual);
                    require(actual.current() == stored.inputVersionId(), "CURRENT_VERSION_CHANGED_AFTER_COMMIT");
                    var current = actual.versions().stream().filter(v -> v.id() == actual.current()).findFirst().orElseThrow();
                    LocalFactVerifyMain.verifyStoredEvidence(current.manifest(), local);
                    LocalFactVerifyMain.verifyStoredEvidence(current.material(), local);
                    verifyEvidence(ready.get("evidence"), local);
                    verifyFile(preflight, suppliedBytes.length, arguments.preflightSha(), local);
                    connection.commit(); // Ends the separate read-only snapshot; no write transaction is retried.
                }
                receipt.put("independentVerification", "MATCHED_CURRENT");
                status = Status.CURRENT_VERIFIED;
            }
        } catch (Exception failure) {
            if (transactionReturned) {
                status = Status.COMMITTED_UNVERIFIED;
                receipt.put("independentVerification", "FAILED_OR_NOT_COMPLETED");
                receipt.put("errorCode", "POSTCOMMIT_VERIFICATION_FAILED");
            } else if (transactionAttempted && !(failure instanceof Rejection)) {
                status = Status.EXECUTION_UNCERTAIN;
                receipt.put("errorCode", "TRANSACTION_COMPLETION_UNCERTAIN");
            } else {
                status = Status.REJECTED;
                receipt.put("errorCode", failure instanceof Rejection rejection ? rejection.code : "LOCAL_REPAIR_PREFLIGHT_REJECTED");
            }
        }
        receipt.put("status", status.name());
        receipt.put("finishedAt", Instant.now().toString());
        writeReceipt(output, receipt, local);
        return new Result(status, output);
    }

    private static Before lockAndCheck(JdbcTemplate jdbc, PreparedFactBatch batch, String expected) {
        jdbc.update("INSERT INTO scsj_fact_day_state (account_id, stat_date) VALUES (?, ?) ON DUPLICATE KEY UPDATE account_id = account_id",
                batch.accountId(), Date.valueOf(batch.statDate()));
        Before pointers = jdbc.queryForObject("SELECT current_input_version_id, previous_input_version_id FROM scsj_fact_day_state WHERE account_id = ? AND stat_date = ? FOR UPDATE",
                (row, number) -> new Before(row.getObject(1, Long.class), row.getObject(2, Long.class), null),
                batch.accountId(), Date.valueOf(batch.statDate()));
        require(pointers != null, "CURRENT_POINTER_REJECTED");
        String hash = null;
        if (pointers.current() == null) {
            require(pointers.previous() == null, "CURRENT_POINTER_REJECTED");
        } else {
            var hashes = jdbc.query("SELECT content_hash FROM scsj_fact_input_version WHERE id = ? AND account_id = ? AND stat_date = ? AND disposition = 'CURRENT' FOR UPDATE",
                    (row, number) -> row.getString(1), pointers.current(), batch.accountId(), Date.valueOf(batch.statDate()));
            require(hashes.size() == 1 && hashes.getFirst().matches("[a-f0-9]{64}"), "CURRENT_POINTER_REJECTED");
            hash = hashes.getFirst();
        }
        require(expected.equals(hash == null ? "MISSING" : hash), "CURRENT_EXPECTATION_CHANGED");
        return new Before(pointers.current(), pointers.previous(), hash);
    }

    private static LocalFactImportMain.ImportPlan executionPlan(Path config, Path root, Path local, PreparedFactBatch batch) throws Exception {
        JsonNode node = object(Files.readAllBytes(config));
        require(batch.accountId().equals(text(node, "expectedAccountId")), "CONFIG_IDENTITY_CHANGED");
        String schema = text(node, "schemaName");
        require(schema.matches("SCSJ_LOCAL_[A-Z0-9_]+") && schema.length() <= 64, "SCHEMA_REJECTED");
        JsonNode credentials = object(Files.readAllBytes(fileWithin(Path.of(text(node, "credentialsPath")), local)));
        require(credentials.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("username", "password", "port")), "CREDENTIALS_REJECTED");
        JsonNode password = credentials.get("password");
        JsonNode port = credentials.get("port");
        require(password != null && password.isString() && port != null && port.isIntegralNumber()
                && port.canConvertToInt() && port.intValue() >= 1024 && port.intValue() <= 65535, "CREDENTIALS_REJECTED");
        return new LocalFactImportMain.ImportPlan(root, local, schema, batch.runId(), batch.accountId(),
                new LocalFactImportMain.Credentials(text(credentials, "username"), password.stringValue(), port.intValue()), List.of());
    }

    private static void verifyTarget(DataSource source, LocalFactImportMain.ImportPlan plan, ObjectNode receipt) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var servers = jdbc.query("SELECT @@port, @@bind_address, HEX(@@datadir)",
                (row, number) -> new Server(row.getInt(1), row.getString(2), row.getString(3)));
        require(servers.size() == 1 && servers.getFirst().port() == plan.credentials().port()
                && "127.0.0.1".equals(servers.getFirst().bind()), "LOCAL_SERVER_REJECTED");
        Path datadir = decodeDataDirectory(servers.getFirst().dataHex()).toRealPath();
        require(datadir.startsWith(plan.localRoot()) && !datadir.equals(plan.localRoot()) && Files.isDirectory(datadir), "SERVER_DATADIR_REJECTED");
        receipt.put("serverDataDirectory", datadir.toString());
        Set<String> tables = new HashSet<>(jdbc.queryForList("SELECT LOWER(TABLE_NAME) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'", String.class, plan.schemaName()));
        require(tables.equals(TABLES), "EXISTING_SCHEMA_REJECTED");
        Integer migration = jdbc.queryForObject("SELECT COUNT(*) FROM scsj_flyway_schema_history WHERE version = '1' AND script = 'V1__create_versioned_material_facts.sql' AND success = 1", Integer.class);
        require(Integer.valueOf(1).equals(migration), "EXISTING_MIGRATION_REJECTED");
        require(Flyway.configure().table("scsj_flyway_schema_history").dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load()
                .validateWithResult().validationSuccessful, "FLYWAY_CHECKSUM_REJECTED");
    }

    static Path decodeDataDirectory(String hexadecimal) throws Exception {
        require(hexadecimal != null && hexadecimal.matches("(?:[A-Fa-f0-9]{2})+"), "DATADIR_ENCODING_REJECTED");
        Charset charset = System.getProperty("os.name", "").startsWith("Windows")
                ? Charset.forName(System.getProperty("native.encoding")) : StandardCharsets.UTF_8;
        String decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(HexFormat.of().parseHex(hexadecimal))).toString();
        require(!decoded.isBlank() && decoded.indexOf('\0') < 0, "DATADIR_ENCODING_REJECTED");
        return Path.of(decoded);
    }

    private static void verifyEvidence(JsonNode evidence, Path local) throws Exception {
        require(evidence != null && evidence.isArray() && !evidence.isEmpty(), "EVIDENCE_REJECTED");
        for (JsonNode item : evidence) {
            JsonNode size = item.get("sizeBytes");
            String hash = text(item, "sha256");
            require(size != null && size.isIntegralNumber() && size.canConvertToLong()
                    && size.longValue() >= 0 && hash.matches("[a-f0-9]{64}"), "EVIDENCE_REJECTED");
            verifyFile(Path.of(text(item, "absolutePath")), size.longValue(), hash, local);
        }
    }

    private static void verifyFile(Path path, long size, String hash, Path local) throws Exception {
        byte[] bytes = Files.readAllBytes(fileWithin(path, local));
        require(bytes.length == size && LocalFactVerifyMain.sha(bytes).equals(hash), "SOURCE_EVIDENCE_CHANGED");
    }

    static Arguments arguments(String[] args) {
        require(args.length == 6 && "--apply".equals(args[5]), "EXPLICIT_SINGLE_DAY_APPLY_REQUIRED");
        require(args[1].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "DATE_REJECTED");
        LocalDate date = LocalDate.parse(args[1]);
        require(date.getYear() >= 1900 && date.getYear() <= 2200, "DATE_REJECTED");
        require(args[3].matches("[a-fA-F0-9]{64}") && ("MISSING".equals(args[4]) || args[4].matches("[a-fA-F0-9]{64}")), "EXPECTED_HASH_REJECTED");
        return new Arguments(Path.of(args[0]), date, Path.of(args[2]), args[3].toLowerCase(java.util.Locale.ROOT),
                "MISSING".equals(args[4]) ? "MISSING" : args[4].toLowerCase(java.util.Locale.ROOT));
    }

    private static ObjectNode initialReceipt(Arguments args) {
        ObjectNode receipt = JSON.createObjectNode().put("schemaVersion", "single-day-repair-receipt-1.0.0")
                .put("statDate", args.date().toString()).put("startedAt", Instant.now().toString()).put("status", "RUNNING")
                .put("actor", "LOCAL_UNAUTHENTICATED").put("expectedCurrent", args.expectedCurrent())
                .put("transactionReturned", false).put("automaticRetry", false).put("automaticRollback", false);
        for (String key : List.of("finishedAt", "preflightEvidence", "sourceType", "contentHash", "rowCount",
                "transactionOutcome", "inputVersionId", "disposition", "independentVerification", "errorCode", "beforeState")) receipt.putNull(key);
        return receipt;
    }

    private static PreparedFactBatch withRun(PreparedFactBatch batch, String run) {
        return new PreparedFactBatch(run, batch.projectRef(), batch.accountId(), batch.statDate(), batch.sourceType(),
                batch.dataVersion(), batch.contentHash(), batch.schemaVersion(), batch.catalogVersion(),
                batch.manifestEvidence(), batch.materialEvidence(), batch.rows());
    }

    private static Path newRun(Path local) throws Exception {
        Path runs = local.resolve("repair-runs");
        if (!Files.exists(runs)) Files.createDirectory(runs);
        require(runs.toRealPath().equals(runs) && Files.isDirectory(runs), "RECEIPT_ROOT_REJECTED");
        Path directory = runs.resolve(UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectory(directory);
        require(directory.toRealPath().equals(directory), "RECEIPT_ROOT_REJECTED");
        return directory;
    }

    private static void writeReceipt(Path path, ObjectNode receipt, Path local) throws Exception {
        require(path.getParent().toRealPath().equals(path.getParent()) && path.startsWith(local), "RECEIPT_PATH_REJECTED");
        if (Files.exists(path)) require(path.toRealPath().equals(path) && Files.isRegularFile(path), "RECEIPT_PATH_REJECTED");
        Path temporary = path.resolveSibling("pending-" + UUID.randomUUID() + ".json");
        Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static ObjectNode evidence(Path path, byte[] bytes) throws Exception {
        return JSON.createObjectNode().put("absolutePath", path.toString()).put("sizeBytes", bytes.length)
                .put("sha256", LocalFactVerifyMain.sha(bytes));
    }

    private static Path fileWithin(Path path, Path local) throws Exception {
        Path real = path.toRealPath();
        require(real.startsWith(local) && !real.equals(local) && Files.isRegularFile(real), "LOCAL_FILE_REJECTED");
        return real;
    }

    private static JsonNode object(byte[] bytes) throws Exception {
        JsonNode result = JSON.readTree(bytes);
        require(result != null && result.isObject(), "JSON_OBJECT_REJECTED");
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        require(value != null && value.isString() && !value.stringValue().isBlank(), "CONFIG_FIELD_REJECTED");
        return value.stringValue();
    }

    private static void nullable(ObjectNode node, String name, Long value) {
        if (value == null) node.putNull(name); else node.put(name, value);
    }
    private static void require(boolean condition, String code) { if (!condition) throw new Rejection(code); }
    private static void silenceLogs() {
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
            logs.reset();
            logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        java.util.logging.LogManager.getLogManager().reset();
    }

    enum Status { CURRENT_VERIFIED, STORED_NOT_CURRENT, COMMITTED_UNVERIFIED, REJECTED, EXECUTION_UNCERTAIN }
    record Result(Status status, Path receiptPath) { }
    record Arguments(Path config, LocalDate date, Path preflight, String preflightSha, String expectedCurrent) { }
    interface Hooks {
        Hooks NONE = new Hooks() { };
        default void beforeTransactionCompletion() { }
        default void beforeIndependentVerification() throws Exception { }
    }
    private record Before(Long current, Long previous, String hash) { }
    private record Server(int port, String bind, String dataHex) { }
    static final class Rejection extends RuntimeException {
        final String code;
        Rejection(String code) { super(code); this.code = code; }
    }
}
