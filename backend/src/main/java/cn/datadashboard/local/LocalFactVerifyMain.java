package cn.datadashboard.local;

import cn.datadashboard.ingest.persistence.*;
import cn.datadashboard.ingest.validation.FactInputValidationRequest;
import cn.datadashboard.ingest.validation.FactInputValidator;
import cn.datadashboard.ingest.validation.InputFileEvidence;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Post-commit verification in a separate, read-only database session. No schema or fact writes. */
public final class LocalFactVerifyMain {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private LocalFactVerifyMain() { }

    public static void main(String[] args) {
        // Framework/driver diagnostics can include database keys or SQL parameters.
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
            logs.reset();
            logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        java.util.logging.LogManager.getLogManager().reset();
        int exit;
        try {
            if (args.length != 1) throw new IllegalArgumentException("CONFIG_ARGUMENT_REQUIRED");
            Path current = Path.of("").toRealPath();
            Path workspace = Files.isDirectory(current.resolve("backend")) ? current : current.getParent();
            if (workspace == null || !Files.isRegularFile(workspace.resolve("START_HERE.md"))) {
                throw new IllegalArgumentException("PROJECT_WORKING_DIRECTORY_REQUIRED");
            }
            exit = execute(LocalFactImportMain.readPlan(Path.of(args[0]), workspace));
        } catch (Exception exception) {
            System.err.println("Local fact verification stopped: configuration or receipt preflight failed.");
            exit = 2;
        }
        System.exit(exit);
    }

    static int execute(LocalFactImportMain.ImportPlan plan) throws Exception {
        Path runs = plan.localRoot().resolve("import-runs");
        if (!Files.exists(runs)) Files.createDirectory(runs);
        requireLocal(runs.toRealPath(), plan.localRoot());
        Path directory = runs.resolve("verify-" + plan.runId() + "-" + UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectory(directory);
        requireLocal(directory.toRealPath(), plan.localRoot());
        Path receiptPath = directory.resolve("verification.json");
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schemaVersion", "local-fact-postcommit-verification-1.0.0");
        receipt.put("runId", plan.runId());
        receipt.put("startedAt", Instant.now().toString());
        receipt.put("requestedDays", plan.days().size());
        receipt.put("databaseReadOnly", true);
        receipt.put("status", "RUNNING");
        ArrayNode days = receipt.putArray("days");
        writeReceipt(receiptPath, receipt);
        int verifiedDays = 0;
        long verifiedRows = 0;
        CaptureStore capture = new CaptureStore();
        try (AnnotationConfigApplicationContext inputs = inputContext(capture);
             Connection connection = openReadOnly(plan)) {
            for (LocalFactImportMain.ImportDay day : plan.days()) {
                ObjectNode result = days.addObject();
                result.put("date", day.date().toString());
                result.put("sourceType", day.automatic() ? "MCP_AUTO" : "MANUAL_HISTORY");
                try {
                    PreparedFactBatch expected = prepareInput(plan, day, inputs, capture);
                    DaySnapshot stored = readDay(connection, expected);
                    compare(expected, stored);
                    Version current = stored.versions().stream()
                            .filter(version -> version.id() == stored.current()).findFirst().orElseThrow();
                    verifyStoredEvidence(current.manifest(), plan.localRoot());
                    verifyStoredEvidence(current.material(), plan.localRoot());
                    result.put("status", "MATCHED");
                    result.put("rowCount", stored.facts().size());
                    verifiedDays++;
                    verifiedRows += stored.facts().size();
                } catch (Exception exception) {
                    result.put("status", "MISMATCH_OR_REJECTED");
                    result.put("errorCode", exception instanceof VerificationFailure failure
                            ? failure.code : "INPUT_OR_READBACK_FAILED");
                }
                writeReceipt(receiptPath, receipt);
            }
            connection.commit(); // Completes only the new read-only snapshot.
            receipt.put("verifiedDays", verifiedDays);
            receipt.put("verifiedRows", verifiedRows);
            boolean allMatched = verifiedDays == plan.days().size();
            receipt.put("status", allMatched ? "ALL_INPUT_DAYS_MATCHED" : "PARTIAL_OR_FAILED");
            receipt.put("finishedAt", Instant.now().toString());
            writeReceipt(receiptPath, receipt);
            System.out.println("Local fact verification receipt: " + receiptPath);
            return allMatched ? 0 : 1;
        } catch (Exception exception) {
            receipt.put("status", "STOPPED");
            receipt.put("errorCode", "READ_ONLY_DATABASE_OR_RECEIPT_FAILED");
            receipt.put("verifiedDays", verifiedDays);
            receipt.put("verifiedRows", verifiedRows);
            receipt.put("finishedAt", Instant.now().toString());
            writeReceipt(receiptPath, receipt);
            System.err.println("Local fact verification stopped; see the sanitized receipt: " + receiptPath);
            return 2;
        }
    }

    static String jdbcUrl(LocalFactImportMain.ImportPlan plan) {
        return "jdbc:mysql://127.0.0.1:" + plan.credentials().port() + "/" + plan.schemaName()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&connectTimeout=5000&socketTimeout=30000&allowMultiQueries=false&readOnlyPropagatesToServer=true";
    }

    static Connection openReadOnly(LocalFactImportMain.ImportPlan plan) throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl(plan),
                plan.credentials().username(), plan.credentials().password());
        try {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("SELECT @@transaction_read_only");
                 ResultSet result = query.executeQuery()) {
                require(result.next() && result.getBoolean(1), "READ_ONLY_SESSION_REQUIRED");
            }
            return connection;
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    static AnnotationConfigApplicationContext inputContext(CaptureStore capture) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(FactImportStore.class, () -> capture);
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(context, false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(
                "cn\\.datadashboard\\.ingest\\.(validation\\.FactInputValidator|persistence\\."
                        + "(PreparedFactBatchReader|ManualHistoryPreparedBatchReader|VersionedFactImportService|ManualHistoryImportService))")));
        scanner.scan("cn.datadashboard.ingest");
        context.refresh();
        return context;
    }

    private static PreparedFactBatch prepareInput(LocalFactImportMain.ImportPlan plan,
            LocalFactImportMain.ImportDay day, AnnotationConfigApplicationContext context, CaptureStore capture) throws Exception {
        capture.batch = null;
        if (day.automatic()) {
            requireLocal(day.inputPath().toRealPath(), plan.localRoot());
            requireLocal(day.inputPath().resolve("manifest.json").toRealPath(), plan.localRoot());
            requireLocal(day.inputPath().resolve("material_day.json").toRealPath(), plan.localRoot());
            var request = new FactInputValidationRequest(plan.runId(), day.inputPath(), plan.expectedAccountId(), day.date());
            var validation = context.getBean(FactInputValidator.class).validate(request);
            context.getBean(VersionedFactImportService.class).importValidated(new VersionedFactImportCommand(request, validation));
        } else {
            JsonNode evidence = LocalFactImportMain.verifyManualIdentity(day, plan.localRoot(), plan.expectedAccountId());
            context.getBean(ManualHistoryImportService.class).importReady(new ManualHistoryImportCommand(
                    evidence.get("runId").stringValue(), day.inputPath(), plan.localRoot()));
        }
        require(capture.batch != null, "INPUT_VALIDATION_REJECTED");
        require(plan.expectedAccountId().equals(capture.batch.accountId()) && day.date().equals(capture.batch.statDate()),
                "INPUT_SCOPE_MISMATCH");
        return LocalFactImportMain.storageBatch(capture.batch, plan.storageAccountId());
    }

    static DaySnapshot readDay(Connection connection, PreparedFactBatch expected) throws Exception {
        Long current;
        Long previous;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT current_input_version_id, previous_input_version_id FROM scsj_fact_day_state
                WHERE account_id = ? AND stat_date = ?
                """)) {
            bindDay(query, expected);
            try (ResultSet rows = query.executeQuery()) {
                require(rows.next(), "DAY_POINTER_MISSING");
                current = (Long) rows.getObject(1, Long.class);
                previous = (Long) rows.getObject(2, Long.class);
                require(!rows.next(), "DUPLICATE_DAY_POINTER");
            }
        }
        require(current != null, "CURRENT_POINTER_MISSING");
        List<Version> versions = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, account_id, stat_date, project_ref, source_type, content_hash, disposition, row_count,
                       manifest_path, manifest_size_bytes, manifest_sha256, material_path, material_size_bytes, material_sha256
                FROM scsj_fact_input_version WHERE account_id = ? AND stat_date = ?
                """)) {
            bindDay(query, expected);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) versions.add(new Version(rows.getLong(1), rows.getString(2), rows.getDate(3).toLocalDate(),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getInt(8),
                        new InputFileEvidence("source", rows.getString(9), rows.getLong(10), rows.getString(11)),
                        new InputFileEvidence("facts", rows.getString(12), rows.getLong(13), rows.getString(14))));
            }
        }
        List<Fact> facts = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT input_version_id, account_id, stat_date, material_id, source_row_number, raw_fact_json, row_sha256
                FROM scsj_material_day_fact WHERE input_version_id = ? ORDER BY source_row_number
                """)) {
            query.setLong(1, current);
            query.setQueryTimeout(30);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) facts.add(new Fact(rows.getLong(1), rows.getString(2), rows.getDate(3).toLocalDate(),
                        rows.getString(4), rows.getInt(5), rows.getString(6), rows.getString(7)));
            }
        }
        return new DaySnapshot(current, previous, versions, facts);
    }

    private static void bindDay(PreparedStatement query, PreparedFactBatch expected) throws Exception {
        query.setString(1, expected.accountId());
        query.setDate(2, java.sql.Date.valueOf(expected.statDate()));
        query.setQueryTimeout(30);
    }

    static void compare(PreparedFactBatch expected, DaySnapshot actual) throws Exception {
        require(actual.current() != null && !Objects.equals(actual.current(), actual.previous()), "POINTER_MISMATCH");
        List<Version> currents = actual.versions().stream().filter(v -> "CURRENT".equals(v.disposition())).toList();
        List<Version> previous = actual.versions().stream().filter(v -> "PREVIOUS".equals(v.disposition())).toList();
        require(currents.size() == 1 && currents.getFirst().id() == actual.current(), "CURRENT_VERSION_MISMATCH");
        require(actual.previous() == null ? previous.isEmpty()
                : previous.size() == 1 && previous.getFirst().id() == actual.previous(), "PREVIOUS_VERSION_MISMATCH");
        Version version = currents.getFirst();
        require(expected.accountId().equals(version.account()) && expected.statDate().equals(version.date())
                        && expected.projectRef().equals(version.project()) && expected.sourceType().name().equals(version.source()),
                "VERSION_IDENTITY_OR_SOURCE_MISMATCH");
        require(expected.contentHash().equals(version.hash()), "VERSION_CONTENT_HASH_MISMATCH");
        require(expected.rows().size() == version.count() && actual.facts().size() == version.count(), "FACT_COUNT_MISMATCH");
        ArrayNode storedRows = JSON.createArrayNode();
        HashSet<String> materialIds = new HashSet<>();
        for (int index = 0; index < expected.rows().size(); index++) {
            PreparedMaterialFact input = expected.rows().get(index);
            Fact fact = actual.facts().get(index);
            require(fact.version() == actual.current() && expected.accountId().equals(fact.account())
                            && expected.statDate().equals(fact.date()) && fact.row() == index + 1
                            && input.materialId().equals(fact.material()) && materialIds.add(fact.material()),
                    "FACT_IDENTITY_OR_ORDER_MISMATCH");
            require(fact.raw() != null && sha(fact.raw().getBytes(StandardCharsets.UTF_8)).equals(fact.sha()), "FACT_BYTES_HASH_MISMATCH");
            JsonNode stored = JSON.readTree(fact.raw());
            JsonNode source = JSON.readTree(input.rawFactJson());
            require(stored != null && stored.isObject() && source != null && source.isObject(), "FACT_JSON_MISMATCH");
            boolean samePayload = expected.sourceType() == FactSourceType.MANUAL_HISTORY
                    ? input.rawFactJson().equals(fact.raw())
                    : canonical(stored).equals(canonical(source));
            require(samePayload, "FACT_JSON_MISMATCH");
            storedRows.add(stored);
        }
        // Manual JSONL is already canonicalized by its Python producer. Preserve numeric token spelling.
        byte[] content = expected.sourceType() == FactSourceType.MANUAL_HISTORY
                ? ("[" + String.join(",", actual.facts().stream().map(Fact::raw).toList()) + "]").getBytes(StandardCharsets.UTF_8)
                : JSON.writeValueAsBytes(canonical(storedRows));
        require(sha(content).equals(version.hash()), "RECONSTRUCTED_CONTENT_HASH_MISMATCH");
    }

    static void verifyStoredEvidence(InputFileEvidence evidence, Path localRoot) throws Exception {
        Path path = Path.of(evidence.absolutePath()).toRealPath();
        requireLocal(path, localRoot);
        byte[] bytes = Files.readAllBytes(path);
        require(bytes.length == evidence.sizeBytes() && sha(bytes).equals(evidence.sha256()), "STORED_SOURCE_EVIDENCE_CHANGED");
    }

    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            node.properties().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            for (JsonNode item : node) array.add(canonical(item));
            return array;
        }
        return node.deepCopy();
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void requireLocal(Path path, Path root) {
        require(path.startsWith(root) && !path.equals(root), "PATH_OUTSIDE_LOCAL_ROOT");
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VerificationFailure(code);
    }

    private static void writeReceipt(Path path, ObjectNode receipt) throws Exception {
        Path pending = path.resolveSibling("verification.pending.json");
        Files.write(pending, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static final class CaptureStore implements FactImportStore {
        private PreparedFactBatch batch;
        @Override public FactStoreResult importAtomically(PreparedFactBatch incoming) {
            batch = incoming;
            return new FactStoreResult(0, FactVersionDisposition.CURRENT, FactStoreOutcome.IDEMPOTENT, incoming.rows().size());
        }
    }

    static final class VerificationFailure extends RuntimeException {
        final String code;
        VerificationFailure(String code) { super(code); this.code = code; }
    }

    record DaySnapshot(Long current, Long previous, List<Version> versions, List<Fact> facts) { }
    record Version(long id, String account, LocalDate date, String project, String source, String hash,
                   String disposition, int count, InputFileEvidence manifest, InputFileEvidence material) { }
    record Fact(long version, String account, LocalDate date, String material, int row, String raw, String sha) { }
}
