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
import tools.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Single configured day, source validation only: no JDBC, credentials read, import or activation. */
public final class LocalFactPreflightMain {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> CONFIG_FIELDS = Set.of("workspaceRoot", "credentialsPath", "schemaName",
            "runId", "expectedAccountId", "autoDays", "manualDays");

    private LocalFactPreflightMain() { }

    public static void main(String[] args) {
        silenceLogs();
        int exit;
        try {
            Path current = Path.of("").toRealPath();
            Path workspace = Files.isDirectory(current.resolve("backend")) ? current : current.getParent();
            require(workspace != null && Files.isRegularFile(workspace.resolve("START_HERE.md"))
                    && Files.isRegularFile(workspace.resolve("backend/pom.xml")));
            exit = run(args, workspace, System.out, System.err);
        } catch (Exception ignored) {
            System.err.println("Local source preflight stopped: workspace or receipt preflight failed.");
            exit = 2;
        }
        System.exit(exit);
    }

    static int run(String[] args, Path workspace, PrintStream out, PrintStream err) {
        try {
            require(args.length == 2);
            Result result = execute(Path.of(args[0]), args[1], workspace);
            out.println("Local source preflight receipt: " + result.receiptPath());
            return result.status() == Status.SOURCE_READY ? 0
                    : result.status() == Status.NO_CONFIGURED_INPUT ? 3 : 1;
        } catch (Exception ignored) {
            // Untrusted JSON, path strings and exception messages never reach the console.
            err.println("Local source preflight stopped: arguments, local paths or receipt preflight failed.");
            return 2;
        }
    }

    static Result execute(Path configPath, String requestedDate, Path workspace) throws Exception {
        LocalDate date = strictDate(requestedDate);
        Path root = workspace.toRealPath();
        Path local = root.resolve(".local").toRealPath();
        require(local.equals(root.resolve(".local")) && Files.isDirectory(local));
        Path directory = receiptDirectory(local);
        Path receiptPath = directory.resolve("preflight.json");
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schemaVersion", "local-source-preflight-1.0.0");
        receipt.put("statDate", date.toString());
        receipt.put("startedAt", Instant.now().toString());
        receipt.put("databaseConnected", false);
        receipt.put("importExecuted", false);
        receipt.put("activationAuthorized", false);
        receipt.put("boundary", "SOURCE_READY validates only the selected configured source; it does not establish publication or authorize activation. NO_CONFIGURED_INPUT does not prove no archive exists.");
        receipt.putNull("sourceType");
        receipt.putNull("contentHash");
        receipt.putNull("rowCount");
        List<InputFileEvidence> evidence = new ArrayList<>();
        Status status;
        try {
            Path config = fileWithin(configPath, local);
            remember(config, "importConfig", local, evidence);
            Selection selected = select(object(config), root, date);
            if (selected == null) {
                unchanged(evidence, local);
                status = Status.NO_CONFIGURED_INPUT;
            } else {
                receipt.put("sourceType", selected.automatic() ? "MCP_AUTO" : "MANUAL_HISTORY");
                Path input = selected.inputPath().toRealPath();
                requireWithin(input, local);
                String manualRun = null;
                if (selected.automatic()) {
                    require(Files.isDirectory(input));
                    remember(input.resolve("manifest.json"), "manifest", local, evidence);
                    remember(input.resolve("material_day.json"), "materialDay", local, evidence);
                } else {
                    remember(input, "dayEvidence", local, evidence);
                    JsonNode day = object(input);
                    require(selected.account().equals(text(day, "accountId"))
                            && date.equals(strictDate(text(day, "statDate"))));
                    manualRun = text(day, "runId");
                    for (String field : List.of("sourceWorkbookEvidence", "factsFileEvidence",
                            "accountIdentityEvidence", "completenessEvidence")) {
                        remember(Path.of(text(day.get(field), "absolutePath")), field, local, evidence);
                    }
                }
                CaptureStore capture = new CaptureStore();
                FactImportReceipt preparation;
                try (var context = inputContext(capture)) {
                    if (selected.automatic()) {
                        var request = new FactInputValidationRequest("source-preflight", input, selected.account(), date);
                        var validation = context.getBean(FactInputValidator.class).validate(request);
                        preparation = context.getBean(VersionedFactImportService.class)
                                .importValidated(new VersionedFactImportCommand(request, validation));
                    } else {
                        preparation = context.getBean(ManualHistoryImportService.class)
                                .importReady(new ManualHistoryImportCommand(manualRun, input, local));
                    }
                }
                if (capture.batch == null) {
                    receipt.put("errorCode", preparation.errorCode() == null
                            ? "SOURCE_VALIDATION_REJECTED" : preparation.errorCode().name());
                    status = Status.INPUT_REJECTED;
                } else {
                    PreparedFactBatch batch = capture.batch;
                    require(selected.account().equals(batch.accountId()) && date.equals(batch.statDate())
                            && batch.sourceType() == (selected.automatic() ? FactSourceType.MCP_AUTO : FactSourceType.MANUAL_HISTORY));
                    requireRecorded(batch.manifestEvidence(), evidence);
                    requireRecorded(batch.materialEvidence(), evidence);
                    unchanged(evidence, local);
                    receipt.put("contentHash", batch.contentHash());
                    receipt.put("rowCount", batch.rows().size());
                    status = Status.SOURCE_READY;
                }
            }
        } catch (Exception ignored) {
            status = Status.INPUT_REJECTED;
            receipt.put("errorCode", "LOCAL_INPUT_PREFLIGHT_FAILED");
            receipt.putNull("contentHash");
            receipt.putNull("rowCount");
        }
        receipt.put("status", status.name());
        receipt.put("finishedAt", Instant.now().toString());
        var files = receipt.putArray("evidence");
        for (InputFileEvidence file : evidence) {
            files.addObject().put("role", file.fileName()).put("absolutePath", file.absolutePath())
                    .put("sizeBytes", file.sizeBytes()).put("sha256", file.sha256());
        }
        // Unique run + CREATE_NEW + non-replacing atomic rename retain all earlier receipts.
        require(directory.toRealPath().equals(directory));
        requireWithin(directory, local);
        Path pending = directory.resolve("preflight.pending.json");
        Files.write(pending, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
        require(directory.toRealPath().equals(directory));
        Files.move(pending, receiptPath, StandardCopyOption.ATOMIC_MOVE);
        require(receiptPath.toRealPath().equals(receiptPath));
        return new Result(status, receiptPath);
    }

    private static Selection select(JsonNode config, Path root, LocalDate date) throws Exception {
        var fields = new java.util.HashSet<>(CONFIG_FIELDS);
        if (config.has("storageAccountId")) {
            fields.add("storageAccountId");
            require(text(config, "storageAccountId").matches("[0-9]{1,64}"));
        }
        requireKeys(config, fields);
        require(Path.of(text(config, "workspaceRoot")).toRealPath().equals(root));
        String account = text(config, "expectedAccountId");
        require(account.matches("[0-9]{1,64}"));
        require(text(config, "schemaName").equals("material_dashboard") || text(config, "schemaName").matches("SCSJ_LOCAL_[A-Z0-9_]{1,53}"));
        require(text(config, "runId").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}"));
        text(config, "credentialsPath"); // A compatibility field; deliberately never resolve or open it.
        Selection selected = null;
        for (String field : List.of("autoDays", "manualDays")) {
            JsonNode days = config.get(field);
            require(days != null && days.isArray());
            boolean automatic = field.equals("autoDays");
            String pathField = automatic ? "inputDirectory" : "dayEvidencePath";
            for (JsonNode day : days) {
                requireKeys(day, Set.of("date", pathField));
                LocalDate entryDate = strictDate(text(day, "date"));
                if (!date.equals(entryDate)) continue;
                require(selected == null);
                selected = new Selection(account, automatic, Path.of(text(day, pathField)));
            }
        }
        return selected;
    }

    static AnnotationConfigApplicationContext inputContext(CaptureStore capture) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(FactImportStore.class, () -> capture);
        var scanner = new ClassPathBeanDefinitionScanner(context, false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(
                "cn\\.datadashboard\\.ingest\\.(validation\\.FactInputValidator|persistence\\."
                        + "(PreparedFactBatchReader|ManualHistoryPreparedBatchReader|VersionedFactImportService|ManualHistoryImportService))")));
        scanner.scan("cn.datadashboard.ingest");
        context.refresh();
        return context;
    }

    /** Reuses the strict readers for a single already-preflighted input; never reads credentials. */
    static PreparedFactBatch prepareSelected(Path configPath, String requestedDate, Path workspace) throws Exception {
        Path root = workspace.toRealPath();
        Path local = root.resolve(".local").toRealPath();
        require(local.equals(root.resolve(".local")));
        LocalDate date = strictDate(requestedDate);
        Selection selected = select(object(fileWithin(configPath, local)), root, date);
        require(selected != null);
        Path input = selected.inputPath().toRealPath();
        requireWithin(input, local);
        CaptureStore capture = new CaptureStore();
        try (var context = inputContext(capture)) {
            if (selected.automatic()) {
                fileWithin(input.resolve("manifest.json"), local);
                fileWithin(input.resolve("material_day.json"), local);
                var request = new FactInputValidationRequest("source-preflight", input, selected.account(), date);
                var validation = context.getBean(FactInputValidator.class).validate(request);
                context.getBean(VersionedFactImportService.class).importValidated(new VersionedFactImportCommand(request, validation));
            } else {
                JsonNode day = LocalFactImportMain.verifyManualIdentity(
                        new LocalFactImportMain.ImportDay(date, false, input), local, selected.account());
                context.getBean(ManualHistoryImportService.class).importReady(
                        new ManualHistoryImportCommand(text(day, "runId"), input, local));
            }
        }
        require(capture.batch != null && selected.account().equals(capture.batch.accountId())
                && date.equals(capture.batch.statDate()));
        return capture.batch;
    }

    private static Path receiptDirectory(Path local) throws Exception {
        Path runs = local.resolve("repair-preflight");
        if (!Files.exists(runs)) Files.createDirectory(runs);
        require(runs.toRealPath().equals(runs) && Files.isDirectory(runs));
        Path directory = runs.resolve(UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectory(directory);
        require(directory.toRealPath().equals(directory));
        return directory;
    }

    private static void remember(Path input, String role, Path local, List<InputFileEvidence> evidence) throws Exception {
        Path real = fileWithin(input, local);
        byte[] bytes = Files.readAllBytes(real);
        evidence.add(new InputFileEvidence(role, real.toString(), bytes.length, LocalFactVerifyMain.sha(bytes)));
    }

    private static void unchanged(List<InputFileEvidence> evidence, Path local) throws Exception {
        for (InputFileEvidence item : evidence) {
            Path real = fileWithin(Path.of(item.absolutePath()), local);
            byte[] bytes = Files.readAllBytes(real);
            require(bytes.length == item.sizeBytes() && LocalFactVerifyMain.sha(bytes).equals(item.sha256()));
        }
    }

    private static void requireRecorded(InputFileEvidence selected, List<InputFileEvidence> evidence) {
        require(evidence.stream().anyMatch(file -> file.absolutePath().equals(selected.absolutePath())
                && file.sizeBytes() == selected.sizeBytes() && file.sha256().equals(selected.sha256())));
    }

    private static Path fileWithin(Path candidate, Path local) throws Exception {
        Path real = candidate.toRealPath();
        requireWithin(real, local);
        require(Files.isRegularFile(real));
        return real;
    }

    private static void requireWithin(Path path, Path root) { require(path.startsWith(root) && !path.equals(root)); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("LOCAL_PREFLIGHT_REJECTED"); }

    private static LocalDate strictDate(String value) {
        require(value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        LocalDate date = LocalDate.parse(value);
        require(date.getYear() >= 1900 && date.getYear() <= 2200);
        return date;
    }

    private static JsonNode object(Path path) throws Exception {
        JsonNode value = JSON.readTree(Files.readAllBytes(path));
        require(value != null && value.isObject());
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        require(value != null && value.isString() && !value.stringValue().isBlank());
        return value.stringValue();
    }

    private static void requireKeys(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject() && node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(fields));
    }

    private static void silenceLogs() {
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
            logs.reset();
            logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        java.util.logging.LogManager.getLogManager().reset();
    }

    enum Status { SOURCE_READY, INPUT_REJECTED, NO_CONFIGURED_INPUT }
    record Result(Status status, Path receiptPath) { }
    private record Selection(String account, boolean automatic, Path inputPath) {
        @Override public String toString() { return "Selection[redacted]"; }
    }
    static final class CaptureStore implements FactImportStore {
        private PreparedFactBatch batch;
        @Override public FactStoreResult importAtomically(PreparedFactBatch incoming) {
            require(batch == null);
            batch = incoming;
            // This in-memory sentinel is never serialized as an import receipt or database result.
            return new FactStoreResult(0, FactVersionDisposition.CURRENT, FactStoreOutcome.IDEMPOTENT, incoming.rows().size());
        }
    }
}
