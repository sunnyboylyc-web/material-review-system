package cn.datadashboard.history;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.ingest.persistence.*;
import cn.datadashboard.ingest.validation.InputFileEvidence;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/** Local upload lifecycle. No collector, legacy source mutation or schema initialization. */
public final class HistoryUploadService {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final int LIMIT = 20 * 1024 * 1024;
    private static final java.util.concurrent.Semaphore PREVIEWS = new java.util.concurrent.Semaphore(1);
    private final Path workspace, root, python;
    private final ProjectConfigurationStore store;
    private final HistoryFactGateway gateway;
    private final boolean writable;
    private final Set<String> activeImports = ConcurrentHashMap.newKeySet();
    private static final Set<String> RUNNING = Set.of("IMPORTING", "VERIFYING", "CLEANING");
    public HistoryUploadService(Path workspace, ProjectConfigurationStore store, HistoryFactGateway gateway, Path pythonExecutable, boolean writable) {
        try {
            this.workspace = workspace.toRealPath(); this.root = this.workspace.resolve(".local/history-uploads");
            this.store = store; this.gateway = gateway; this.python = pythonExecutable; this.writable = writable;
            safe(root);
        } catch (Exception e) { throw failure("HISTORY_UNAVAILABLE"); }
    }
    public boolean writable() { return writable; }
    public byte[] template() {
        try (var input = getClass().getResourceAsStream("/templates/manual-history.xlsx")) {
            check(input != null, "TEMPLATE_UNAVAILABLE"); return input.readAllBytes();
        } catch (Exception e) { throw failure("TEMPLATE_UNAVAILABLE"); }
    }
    private record Binding(String revision, ProjectConfigurationStore.Project project) { }
    private Binding binding(String id) {
        var snapshot = store.snapshot();
        var project = snapshot.document().projects().stream().filter(p -> p.id().equals(id) && p.enabled() && !p.deleted()).findFirst()
                .orElseThrow(() -> failure("PROJECT_UNAVAILABLE"));
        return new Binding(snapshot.revision(), project);
    }
    private HistoryFactGateway.Context cumulativeContext(String project) {
        LocalDate first = null;
        Set<LocalDate> dates = new HashSet<>(), manual = new HashSet<>();
        for (String account : store.lineage(project).accounts()) {
            var part = gateway.inspect(account);
            if (part.firstMcpDate() != null && (first == null || part.firstMcpDate().isBefore(first))) first = part.firstMcpDate();
            for (LocalDate date : part.existingDates()) check(dates.add(date), "HISTORY_ACCOUNT_DAY_CONFLICT");
            manual.addAll(part.manualHistoryDates());
        }
        return new HistoryFactGateway.Context(first, dates, manual);
    }

    public ObjectNode context(String id) {
        try {
            var binding = binding(id); var context = cumulativeContext(binding.project.id());
            ObjectNode node = JSON.createObjectNode().put("schemaVersion", "history-upload-api-v1").put("projectId", id)
                    .put("projectName", binding.project.name()).put("accountId", binding.project.storageAccountId())
                    .put("configurationRevision", binding.revision).put("writeEnabled", writable);
            if (context.firstMcpDate() == null) { node.putNull("firstMcpDate"); node.putNull("blockedReason"); }
            else { node.put("firstMcpDate", context.firstMcpDate().toString()); node.putNull("blockedReason"); }
            var existingDates = node.putArray("existingDates"); context.existingDates().stream().sorted().forEach(d -> existingDates.add(d.toString()));
            var manualDates = node.putArray("manualHistoryDates"); context.manualHistoryDates().stream().sorted().forEach(d -> manualDates.add(d.toString()));
            var uploads = node.putArray("uploads");
            var warnings = node.putArray("warnings");
            List<ObjectNode> records = new ArrayList<>();
            if (Files.isDirectory(root)) try (var dirs = Files.list(root)) {
                for (Path dir : dirs.toList()) {
                    if (!dir.getFileName().toString().matches("[a-f0-9]{32}")) continue;
                    try {
                        var state = readState(dir);
                        if (id.equals(state.path("projectId").stringValue())) records.add(view(state));
                    } catch (Exception unavailable) {
                        // Corrupt identity is not trustworthy: expose only a generic count, never another project's details.
                        warnings.addObject().put("code", "UPLOAD_RECORD_UNAVAILABLE")
                                .put("message", "有上传记录暂时无法读取，无法确认文件归属；新上传已阻止，请联系维护人员检查。已有可读记录仍可查看或安全取消。");
                        node.put("blockedReason", "UPLOAD_RECORD_UNAVAILABLE");
                    }
                }
            }
            records.sort(Comparator.comparing(HistoryUploadService::recordTime).reversed()
                    .thenComparing(record -> record.path("uploadId").stringValue()));
            records.forEach(uploads::add);
            return node;
        } catch (Failure e) { throw e; } catch (Exception e) { throw failure("HISTORY_UNAVAILABLE"); }
    }
    public ObjectNode preview(String id, InputStream input) { return preview(id,input,"HISTORY"); }
    public ObjectNode preview(String id, InputStream input, String mode) {
        check(Set.of("HISTORY", "GAP_FILL").contains(mode), "REQUEST_INVALID");
        check(writable, "HISTORY_WRITE_DISABLED");
        check(PREVIEWS.tryAcquire(), "CONVERSION_BUSY");
        try {
            var binding = binding(id); var context = cumulativeContext(binding.project.id());
            safe(root); Files.createDirectories(root); safe(root);
            requireEmptySlot(id);
            String upload = UUID.randomUUID().toString().replace("-", ""); Path dir = root.resolve(upload);
            Files.createDirectory(dir); safe(dir);
            Path source = dir.resolve("upload.xlsx");
            boolean oversized = false, receiveFailed = false;
            try (var output = Files.newOutputStream(source, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; int read, total = 0;
                while ((read = input.read(buffer)) != -1) { total += read; if(total>LIMIT){oversized=true;break;} output.write(buffer, 0, read); }
            } catch (IOException interrupted) {
                // Retain only this new partial copy with readable, hash-bound failure evidence.
                // Never expose transport exception text or pass an incomplete file to conversion.
                receiveFailed = true;
            }
            ObjectNode conversionContext = JSON.createObjectNode().put("projectId", id).put("accountId", binding.project.storageAccountId())
                    .put("firstMcpDate", context.firstMcpDate() == null ? null : context.firstMcpDate().toString());
            var existing = conversionContext.putArray("existingDates"); context.existingDates().stream().sorted().forEach(d -> existing.add(d.toString()));
            conversionContext.put("uploadMode",mode);
            writeNew(dir.resolve("context.json"), conversionContext);
            ObjectNode preview;
            try { check(!receiveFailed,"UPLOAD_RECEIVE_FAILED"); check(!oversized,"UPLOAD_TOO_LARGE"); convert(dir); preview = object(dir.resolve("converted.json")); }
            catch (Exception e) { preview = JSON.createObjectNode().put("status", "VALIDATION_FAILED").put("rowCount", 0).put("totalSpend", "0");
                String code = receiveFailed ? "UPLOAD_RECEIVE_FAILED" : oversized ? "UPLOAD_TOO_LARGE" : e instanceof Failure f && Set.of("CONVERSION_RESOURCE_LIMIT", "CONVERSION_TIMEOUT", "PYTHON_UNAVAILABLE").contains(f.code) ? f.code : "CONVERSION_FAILED";
                preview.putArray("days"); preview.putObject("diagnostic").put("code", code).put("message", switch(code) {
                    case "UPLOAD_RECEIVE_FAILED" -> "上传接收未完成，未核验或入库；请删除本次副本后重新上传";
                    case "UPLOAD_TOO_LARGE" -> "上传超过20MiB，副本未完整接收，请取消";
                    case "CONVERSION_RESOURCE_LIMIT" -> "转换达到内存保护上限，未入库；请勿重复提交，请联系运维";
                    case "CONVERSION_TIMEOUT" -> "转换超时已停止，未入库；请勿重复提交，请联系运维";
                    default -> "文件转换未完成，未入库；请保留副本并联系运维，勿重复提交";
                }); }
            preview.put("uploadMode",mode);
            preview.put("uploadId", upload).put("projectId", id).put("accountId", binding.project.storageAccountId())
                    .put("createdAt", Instant.now().toString())
                    .put("configurationRevision", binding.revision).put("firstMcpDate", context.firstMcpDate() == null ? null : context.firstMcpDate().toString())
                    .put("sourceSha256", sha(Files.readAllBytes(source))).put("sourceSizeBytes", Files.size(source));
            var days = preview.withArray("days");
            if (days.isEmpty()) { preview.putNull("start"); preview.putNull("end"); }
            else { preview.put("start", days.get(0).path("statDate").stringValue()); preview.put("end", days.get(days.size()-1).path("statDate").stringValue()); }
            for (var day : days) ((ObjectNode)day).put("state", "PENDING");
            writeNew(dir.resolve("preview.json"), preview);
            ObjectNode state = preview.deepCopy(); state.put("previewHash", sha(Files.readAllBytes(dir.resolve("preview.json"))))
                    .put("sourceFileState", "RETAINED"); saveState(dir, state); return view(state);
        } catch (Failure e) { throw e; } catch (Exception e) { throw failure("HISTORY_UNAVAILABLE"); }
        finally { PREVIEWS.release(); }
    }
    private void convert(Path dir) throws Exception {
        check(python != null && Files.isRegularFile(python), "PYTHON_UNAVAILABLE");
        Path script = workspace.resolve("tools/manual_history/convert_upload_bridge.py");
        Process process = new ProcessBuilder(python.toString(), "-B", script.toString(), dir.resolve("context.json").toString(),
                dir.resolve("converted.json").toString()).directory(workspace.toFile()).redirectErrorStream(true).start();
        // Drain bounded output without retaining provider/parser messages.
        var drain = java.util.concurrent.CompletableFuture.runAsync(() -> { try (var stream = process.getInputStream()) {
            int bytes = 0; byte[] buffer = new byte[1024]; int n;
            while ((n = stream.read(buffer)) != -1) { bytes += n; if (bytes > 8192) { process.destroyForcibly(); break; } }
        } catch (IOException ignored) { } });
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw failure("CONVERSION_TIMEOUT");
            drain.get(5, TimeUnit.SECONDS);
            check(process.exitValue() != 75, "CONVERSION_RESOURCE_LIMIT");
            check(process.exitValue() == 0, "CONVERSION_FAILED");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    public ObjectNode get(String project, String upload) {
        try {
            binding(project); ObjectNode state = readState(directory(upload));
            check(project.equals(state.path("projectId").stringValue()), "UPLOAD_NOT_FOUND");
            return view(state);
        } catch (Failure e) { throw e; } catch (Exception e) { throw failure("HISTORY_UNAVAILABLE"); }
    }
    public ObjectNode apply(String project, String upload, String previewHash, boolean account, boolean complete) {
        return applyAs(project,upload,previewHash,account,complete,"LOCAL_UNAUTHENTICATED");
    }
    public ObjectNode applyAs(String project, String upload, String previewHash, boolean account, boolean complete, String actor) {
        try { return store.exclusive(() -> applyLocked(project, upload, previewHash, account, complete, actor)); }
        catch (ProjectConfigurationStore.ConfigurationException busy) { throw failure("UPLOAD_BUSY"); }
    }
    private ObjectNode applyLocked(String project, String upload, String previewHash, boolean account, boolean complete, String actor) {
        check(cn.datadashboard.security.OperationActor.valid(actor),"REQUEST_INVALID");
        check(writable, "HISTORY_WRITE_DISABLED"); check(account && complete, "CONFIRMATION_REQUIRED");
        check(upload != null && activeImports.add(upload), "UPLOAD_BUSY");
        try { return locked(project, upload, state -> {
            Path dir = directory(upload); check(Objects.equals(previewHash, state.path("previewHash").stringValue()), "PREVIEW_CHANGED");
            if ("COMPLETED".equals(state.path("status").stringValue())) return view(state);
            check(!Set.of("VALIDATION_FAILED", "CANCELLED").contains(state.path("status").stringValue()), "UPLOAD_NOT_READY");
            var binding = binding(project);
            check(binding.revision.equals(state.path("configurationRevision").stringValue()) && binding.project.storageAccountId().equals(state.path("accountId").stringValue()), "CONFIGURATION_CHANGED");
            var context = cumulativeContext(binding.project.id());
            check(Objects.equals(context.firstMcpDate() == null ? null : context.firstMcpDate().toString(), state.path("firstMcpDate").stringValue()), "MCP_CONTEXT_CHANGED");
            verifySource(dir, state, false);
            if(!state.has("confirmedAt")){state.put("confirmedAt",Instant.now().toString()).put("confirmationActor",actor);saveState(dir,state);}
            state.remove("message");
            int completedDays = 0;
            try {
                // A retry is a new execution attempt, but the immutable confirmation evidence stays unchanged.
                state.putObject("progress").put("startedAt", Instant.now().toString());
                progress(dir, state, "IMPORTING", 0, null);
                for (JsonNode item : state.withArray("days")) {
                    ObjectNode day = (ObjectNode)item; LocalDate date = LocalDate.parse(day.path("statDate").stringValue());
                    progress(dir, state, "IMPORTING", completedDays, date.toString());
                    check(binding(project).revision.equals(binding.revision), "CONFIGURATION_CHANGED");
                    check(date.isBefore("GAP_FILL".equals(state.path("uploadMode").stringValue()) || context.firstMcpDate() == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : context.firstMcpDate()), "HISTORY_CUTOFF");
                    boolean saved = day.has("versionId");
                    PreparedFactBatch batch = batch(dir, state, day);
                    if (!saved) {
                        var recovered = gateway.recoverCurrentDay(batch);
                        if (recovered.isPresent()) day.put("versionId", recovered.getAsLong());
                        else {
                            check(!context.existingDates().contains(date), "HISTORY_OVERLAP");
                            var imported = gateway.importNewDay(batch);
                            check(imported.disposition()==FactVersionDisposition.CURRENT && imported.rowCount()==batch.rows().size(),"IMPORT_RESULT_INVALID");
                            day.put("versionId", imported.inputVersionId());
                        }
                        day.put("state", "VERIFYING");
                        saveState(dir, state);
                    }
                    gateway.verify(batch, day.path("versionId").longValue());
                    day.put("state", "IMPORTED"); progress(dir, state, "IMPORTING", ++completedDays, date.toString());
                }
                progress(dir, state, "VERIFYING", 0, null);
                // Recheck every day independently immediately before touching the upload copy.
                completedDays = 0;
                for (JsonNode day : state.withArray("days")) {
                    progress(dir, state, "VERIFYING", completedDays, day.path("statDate").stringValue());
                    gateway.verify(batch(dir, state, (ObjectNode)day), day.path("versionId").longValue());
                    progress(dir, state, "VERIFYING", ++completedDays, day.path("statDate").stringValue());
                }
                progress(dir, state, "CLEANING", completedDays, null);
                cleanup(dir, state); progress(dir, state, "COMPLETED", completedDays, null);
            } catch (Exception e) {
                if ("IMPORTING".equals(state.path("status").stringValue())) {
                    String failedDate = state.path("progress").path("currentDate").stringValue();
                    for(JsonNode item:state.withArray("days")) if(Objects.equals(failedDate,item.path("statDate").stringValue())) {((ObjectNode)item).put("state","FAILED");break;}
                    state.put("status", "IMPORT_FAILED");
                } else state.put("status", "IMPORTED_CLEANUP_PENDING");
                ((ObjectNode)state.get("progress")).put("updatedAt", Instant.now().toString());
                String stage = state.path("progress").path("stage").stringValue();
                state.put("message", switch (stage) {
                    case "VERIFYING" -> "逐日入库已完成，但最终回读核验未通过；上传副本保留，请检查后重试，不会覆盖成功日期";
                    case "CLEANING" -> "数据入库及最终回读核验已通过，但上传副本清理未完成；请检查后重试";
                    default -> "入库未全部完成，请保留上传副本并检查后重试；成功日期不会覆盖";
                }); saveState(dir, state);
            }
            return view(state);
        }); } finally { activeImports.remove(upload); }
    }
    private void progress(Path dir, ObjectNode state, String stage, int completedDays, String currentDate) throws Exception {
        String startedAt = state.path("progress").path("startedAt").isString() ? state.path("progress").path("startedAt").stringValue() : null;
        ObjectNode progress = state.putObject("progress").put("stage", stage).put("completedDays", completedDays)
                .put("totalDays", state.path("days").size()).put("startedAt", startedAt == null ? Instant.now().toString() : startedAt)
                .put("updatedAt", Instant.now().toString());
        if (currentDate == null) progress.putNull("currentDate"); else progress.put("currentDate", currentDate);
        state.put("status", stage);
        if ("COMPLETED".equals(stage)) state.put("completedAt", Instant.now().toString());
        saveState(dir, state);
    }
    public ObjectNode discard(String project, String upload, String hash) {
        check(writable, "HISTORY_WRITE_DISABLED");
        return locked(project, upload, state -> { check(Objects.equals(hash, state.path("previewHash").stringValue()), "PREVIEW_CHANGED");
            check(!"COMPLETED".equals(state.path("status").stringValue()), "UPLOAD_ALREADY_COMPLETED");
            check(!activeImports.contains(upload), "UPLOAD_BUSY");
            check(Set.of("PREVIEW_READY", "VALIDATION_FAILED").contains(state.path("status").stringValue())
                    && !state.has("confirmedAt") && !state.has("progress")
                    && java.util.stream.StreamSupport.stream(state.path("days").spliterator(), false)
                        .allMatch(day -> !day.has("versionId") && "PENDING".equals(day.path("state").stringValue())), "UPLOAD_DISCARD_UNSAFE");
            cleanup(directory(upload), state); state.put("status", "CANCELLED"); saveState(directory(upload), state); return view(state); });
    }
    /** Never trust an unreadable record's identity to exempt another project's new upload. */
    private void requireEmptySlot(String project) throws Exception {
        boolean occupied = false;
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                if (!dir.getFileName().toString().matches("[a-f0-9]{32}")) continue;
                ObjectNode state;
                try {
                    safe(dir); check(Files.isDirectory(dir), "UPLOAD_RECORD_UNAVAILABLE");
                    state = readState(dir);
                    check(state.path("projectId").isString() && !state.path("projectId").stringValue().isBlank(), "UPLOAD_RECORD_UNAVAILABLE");
                } catch (Exception invalid) { throw failure("UPLOAD_RECORD_UNAVAILABLE"); }
                if (project.equals(state.path("projectId").stringValue())
                        && (!Set.of("COMPLETED", "CANCELLED").contains(state.path("status").stringValue())
                            || "RETAINED".equals(state.path("sourceFileState").stringValue())
                            || Files.exists(dir.resolve("upload.xlsx"), LinkOption.NOFOLLOW_LINKS))) occupied = true;
            }
        }
        check(!occupied, "UPLOAD_SLOT_OCCUPIED");
    }
    private PreparedFactBatch batch(Path dir, ObjectNode state, ObjectNode day) throws Exception {
        String date = day.path("statDate").stringValue(); LocalDate.parse(date);
        Path facts = dir.resolve(date).resolve("facts.jsonl"); safe(facts);
        check(Files.size(facts) == day.path("factsSizeBytes").longValue() && sha(Files.readAllBytes(facts)).equals(day.path("factsSha256").stringValue()), "FACTS_CHANGED");
        List<PreparedMaterialFact> rows = new ArrayList<>();
        for (String line : Files.readAllLines(facts, StandardCharsets.UTF_8)) {
            JsonNode row = JSON.readTree(line); check(date.equals(row.path("stat_time_day").stringValue()) && row.path("material_id").isString(), "FACTS_CHANGED");
            rows.add(new PreparedMaterialFact(row.path("material_id").stringValue(), rows.size()+1, line, sha(line.getBytes(StandardCharsets.UTF_8))));
        }
        String contentHash = sha(("[" + String.join(",", rows.stream().map(PreparedMaterialFact::rawFactJson).toList()) + "]").getBytes(StandardCharsets.UTF_8));
        check(rows.size() == day.path("rowCount").intValue() && contentHash.equals(day.path("contentHash").stringValue()), "FACTS_CHANGED");
        Path manifest = dir.resolve(date).resolve("normalized-manifest-v2.json");
        ObjectNode evidence = JSON.createObjectNode().put("schemaVersion", "manual-history-normalized-manifest-2.0.0")
                .put("sourceType", "MANUAL_HISTORY").put("projectId", state.path("projectId").stringValue())
                .put("accountId", state.path("accountId").stringValue()).put("configurationRevision", state.path("configurationRevision").stringValue())
                .put("statDate", date).put("sourceSha256", state.path("sourceSha256").stringValue()).put("sourceSizeBytes", state.path("sourceSizeBytes").longValue())
                .put("confirmAccount", true).put("confirmCompleteDays", true).put("declaredCompleteness", day.path("declaredCompleteness").stringValue())
                .put("confirmedAt",state.path("confirmedAt").stringValue()).put("confirmationActor",state.has("confirmationActor")?state.path("confirmationActor").stringValue():"LOCAL_UNAUTHENTICATED")
                .put("factsSha256", day.path("factsSha256").stringValue()).put("factsSizeBytes", day.path("factsSizeBytes").longValue())
                .put("contentHash", contentHash).put("rowCount", rows.size()).put("previewHash", state.path("previewHash").stringValue());
        if (Files.exists(manifest)) { safe(manifest); check(Arrays.equals(Files.readAllBytes(manifest),JSON.writeValueAsBytes(evidence)), "MANIFEST_CHANGED"); } else writeNew(manifest, evidence);
        return new PreparedFactBatch(state.path("uploadId").stringValue(), state.path("projectId").stringValue(), state.path("accountId").stringValue(),
                LocalDate.parse(date), FactSourceType.MANUAL_HISTORY, "manual-upload-" + date + "-" + contentHash.substring(0,12), contentHash,
                "qianchuan-material-day-standard-1.0.0", state.path("mappingVersion").stringValue(), fileEvidence(manifest), fileEvidence(facts), rows);
    }
    private void verifySource(Path dir, ObjectNode state, boolean deleting) throws Exception {
        Path source = dir.resolve("upload.xlsx"); safe(source);
        if (!Files.exists(source)) { check("DELETED".equals(state.path("sourceFileState").stringValue()) || Set.of("IMPORTED_CLEANUP_PENDING", "CLEANING").contains(state.path("status").stringValue()), "SOURCE_MISSING"); return; }
        check(Files.size(source) == state.path("sourceSizeBytes").longValue() && sha(Files.readAllBytes(source)).equals(state.path("sourceSha256").stringValue()), "SOURCE_CHANGED");
    }
    private void cleanup(Path dir, ObjectNode state) throws Exception {
        verifySource(dir, state, true); Path source = dir.resolve("upload.xlsx");
        if (Files.exists(source)) Files.delete(source);
        check(!Files.exists(source, LinkOption.NOFOLLOW_LINKS), "CLEANUP_FAILED"); state.put("sourceFileState", "DELETED");
    }
    private interface Action { ObjectNode run(ObjectNode state) throws Exception; }
    private ObjectNode locked(String project, String upload, Action action) {
        try {
            binding(project); Path dir = directory(upload);
            try (var channel = FileChannel.open(dir.resolve("upload.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                check(lock != null, "UPLOAD_BUSY"); ObjectNode state = readState(dir);
                check(project.equals(state.path("projectId").stringValue()), "UPLOAD_NOT_FOUND"); return action.run(state);
            }
        } catch (Failure e) { throw e; } catch (java.nio.channels.OverlappingFileLockException e) { throw failure("UPLOAD_BUSY"); }
        catch (Exception e) { throw failure("HISTORY_UNAVAILABLE"); }
    }
    private Path directory(String id) throws Exception { check(id != null && id.matches("[a-f0-9]{32}"), "UPLOAD_NOT_FOUND"); Path dir=root.resolve(id); safe(dir); check(Files.isDirectory(dir), "UPLOAD_NOT_FOUND"); return dir; }
    private ObjectNode readState(Path dir) throws Exception {
        ObjectNode envelope = object(dir.resolve("state.json")); ObjectNode state = (ObjectNode)envelope.get("state");
        check(state != null && sha(JSON.writeValueAsBytes(state)).equals(envelope.path("sha256").stringValue()), "STATE_CHANGED");
        check(sha(Files.readAllBytes(dir.resolve("preview.json"))).equals(state.path("previewHash").stringValue()), "PREVIEW_CHANGED");
        ObjectNode preview = object(dir.resolve("preview.json"));
        check(state.path("projectId").isString() && !state.path("projectId").stringValue().isBlank()
                && state.path("accountId").isString() && !state.path("accountId").stringValue().isBlank()
                && state.path("status").isString() && state.path("sourceFileState").isString(), "STATE_CHANGED");
        for (String key : List.of("uploadMode", "projectId", "accountId", "configurationRevision", "sourceSha256", "sourceSizeBytes", "firstMcpDate", "mappingVersion", "duplicateRowsSkipped", "duplicatePolicy"))
            check(Objects.equals(preview.get(key), state.get(key)), "STATE_CHANGED");
        for (int i=0; i<preview.path("days").size(); i++) for (String key : List.of("statDate", "rowCount", "totalSpend", "declaredCompleteness", "factsSha256", "factsSizeBytes", "contentHash"))
            check(Objects.equals(preview.path("days").get(i).get(key), state.path("days").path(i).get(key)), "STATE_CHANGED");
        check(preview.path("days").size()==state.path("days").size(), "STATE_CHANGED");
        check(dir.getFileName().toString().equals(state.path("uploadId").stringValue()), "STATE_CHANGED");
        if (preview.has("createdAt")) check(Objects.equals(preview.get("createdAt"), state.get("createdAt")), "STATE_CHANGED");
        return state;
    }
    private void saveState(Path dir, ObjectNode state) throws Exception {
        ObjectNode envelope = JSON.createObjectNode().put("sha256", sha(JSON.writeValueAsBytes(state))); envelope.set("state", state);
        Path pending=dir.resolve("state-"+UUID.randomUUID()+".pending"); writeNew(pending,envelope);
        Files.move(pending,dir.resolve("state.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); readState(dir);
    }
    private ObjectNode view(ObjectNode state) {
        ObjectNode result = state.deepCopy(); result.remove(List.of("mappingVersion","confirmedAt"));
        // Compatibility is view-only: do not rewrite old signed state/preview evidence to add timestamps.
        try {
            Path dir = directory(state.path("uploadId").stringValue());
            Instant created = instant(state.path("createdAt"));
            if (created == null) created = Files.getLastModifiedTime(dir.resolve("preview.json")).toInstant();
            result.put("createdAt", created.toString());
            Instant completed = instant(state.path("completedAt"));
            if ("COMPLETED".equals(state.path("status").stringValue()) && completed == null) {
                completed = instant(state.path("progress").path("updatedAt"));
                if (completed == null) completed = Files.getLastModifiedTime(dir.resolve("state.json")).toInstant();
            }
            if (completed == null) result.putNull("completedAt"); else result.put("completedAt", completed.toString());
        } catch (Exception failure) { throw failure("HISTORY_UNAVAILABLE"); }
        String status = state.path("status").stringValue();
        boolean active = activeImports.contains(state.path("uploadId").stringValue());
        result.put("active", active && !Set.of("COMPLETED", "IMPORT_FAILED", "IMPORTED_CLEANUP_PENDING").contains(status));
        if (RUNNING.contains(status) && !active) result.put("status", "INTERRUPTED");
        for (JsonNode day : result.withArray("days")) ((ObjectNode)day).remove(List.of("versionId", "factsSha256", "factsSizeBytes", "contentHash", "zeroSpendRowCount"));
        return result;
    }
    private static Instant instant(JsonNode value) { try { return Instant.parse(value.stringValue()); } catch (Exception invalid) { return null; } }
    private static Instant recordTime(ObjectNode record) {
        Instant completed = instant(record.path("completedAt"));
        return completed == null ? Instant.parse(record.path("createdAt").stringValue()) : completed;
    }
    private ObjectNode object(Path path) throws Exception { safe(path); check(Files.size(path)<=100L*1024*1024, "FILE_LIMIT"); return (ObjectNode)JSON.readTree(Files.readAllBytes(path)); }
    private void writeNew(Path path, JsonNode node) throws Exception { safe(path); byte[] data=JSON.writeValueAsBytes(node); Files.write(path,data,StandardOpenOption.CREATE_NEW); check(Arrays.equals(data,Files.readAllBytes(path)),"READBACK_FAILED"); }
    private InputFileEvidence fileEvidence(Path path) throws Exception { safe(path); return new InputFileEvidence(path.getFileName().toString(),path.toString(),Files.size(path),sha(Files.readAllBytes(path))); }
    private void safe(Path path) throws Exception {
        check(path.toAbsolutePath().normalize().equals(path) && path.startsWith(workspace.resolve(".local")), "PATH_REJECTED");
        Path cursor=workspace; for(Path part:workspace.relativize(path)) { cursor=cursor.resolve(part); if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)) check(!Files.isSymbolicLink(cursor)&&cursor.toRealPath().equals(cursor),"PATH_REJECTED"); }
    }
    private static String sha(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    public static void check(boolean valid,String code) { if(!valid) throw failure(code); }
    public static Failure failure(String code) { return new Failure(code); }
    public static final class Failure extends RuntimeException { public final String code; Failure(String code){super(code);this.code=code;} }
}
