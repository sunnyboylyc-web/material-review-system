package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.InputFileEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Explicit upload gateway; never invokes the legacy replacement/idempotency path. */
public final class JdbcHistoryFactGateway implements HistoryFactGateway {
    private final DataSource dataSource;

    public JdbcHistoryFactGateway(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override public Context inspect(String accountId) {
        try {
            if (accountId == null || accountId.isBlank() || accountId.length() > 64) throw rejected("ACCOUNT_REQUIRED");
            // Context needs only verified pointer/version headers, never all raw material rows.
            // LEFT JOIN makes broken pointers fail closed instead of silently looking like no MCP.
            return readOnly(jdbc -> {
                var days = jdbc.query("""
                    SELECT s.account_id AS state_account, s.stat_date AS state_date,
                           s.current_input_version_id AS pointer_id, v.id AS version_id,
                           v.account_id AS version_account, v.stat_date AS version_date,
                           v.source_type, v.disposition, v.content_hash, v.row_count
                    FROM scsj_fact_day_state s
                    LEFT JOIN scsj_fact_input_version v ON v.id = s.current_input_version_id
                    WHERE s.account_id = ? AND s.current_input_version_id IS NOT NULL
                    """, (r,n) -> {
                    Long pointer = r.getObject("pointer_id", Long.class), version = r.getObject("version_id", Long.class);
                    var day = r.getDate("state_date"); var versionDay = r.getDate("version_date");
                    String source = r.getString("source_type"), hash = r.getString("content_hash");
                    Integer count = r.getObject("row_count", Integer.class);
                    if (pointer == null || pointer <= 0 || !pointer.equals(version) || day == null || !day.equals(versionDay)
                            || !accountId.equals(r.getString("state_account")) || !accountId.equals(r.getString("version_account"))
                            || !("MCP_AUTO".equals(source) || "MANUAL_HISTORY".equals(source))
                            || !"CURRENT".equals(r.getString("disposition")) || hash == null || !hash.matches("[0-9a-f]{64}")
                            || count == null || count < 0) throw rejected("HISTORY_CURRENT_METADATA_INVALID");
                    return new CurrentDay(day.toLocalDate(), source);
                }, accountId);
                if (days.stream().map(CurrentDay::date).distinct().count() != days.size()) throw rejected("HISTORY_DUPLICATE_DAY");
                LocalDate first = days.stream().filter(d -> "MCP_AUTO".equals(d.source())).map(CurrentDay::date).min(LocalDate::compareTo).orElse(null);
                return new Context(first, new HashSet<>(jdbc.query(
                    "SELECT DISTINCT stat_date FROM scsj_fact_input_version WHERE account_id = ?",
                    (rs, n) -> rs.getDate(1).toLocalDate(), accountId)),
                    days.stream().filter(d -> "MANUAL_HISTORY".equals(d.source())).map(CurrentDay::date).collect(java.util.stream.Collectors.toSet()));
            });
        } catch (Exception failure) { throw rejected("HISTORY_CONTEXT_UNAVAILABLE"); }
    }
    record CurrentDay(LocalDate date, String source) { }

    @Override public FactStoreResult importNewDay(PreparedFactBatch batch) {
        validateBatch(batch);
        Context context = inspect(batch.accountId());
        LocalDate cutoff = context.firstMcpDate() == null ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")) : context.firstMcpDate();
        if (!batch.statDate().isBefore(cutoff))
            throw rejected("HISTORY_MCP_CUTOFF_INVALID");
        if (context.existingDates().contains(batch.statDate())) throw rejected("HISTORY_DAY_ALREADY_EXISTS");
        try {
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var store = new JdbcFactImportStore(new JdbcTemplate(dataSource), new VersionPlacementPolicy(),
                    new StoredFactVersionVerifier());
            return transaction.execute(status -> store.importNewHistoryDay(batch, context.firstMcpDate()));
        } catch (Exception failure) { throw rejected("HISTORY_IMPORT_REJECTED"); }
    }

    @Override public OptionalLong recoverCurrentDay(PreparedFactBatch batch) {
        validateBatch(batch);
        List<Long> ids = readOnly(jdbc -> jdbc.query("""
                SELECT current_input_version_id FROM scsj_fact_day_state
                WHERE account_id = ? AND stat_date = ? AND current_input_version_id IS NOT NULL
                """, (r,n) -> r.getLong(1), batch.accountId(), java.sql.Date.valueOf(batch.statDate())));
        if (ids.isEmpty()) return OptionalLong.empty();
        if (ids.size() != 1) throw rejected("HISTORY_READBACK_FAILED");
        // Includes project/source, account/day, exact immutable upload evidence paths/hashes,
        // CURRENT pointer, no previous version, all rows and content hash. Same content alone is insufficient.
        verify(batch, ids.get(0));
        return OptionalLong.of(ids.get(0));
    }

    @Override public void verify(PreparedFactBatch batch, long versionId) {
        validateBatch(batch);
        try {
            readOnly(jdbc -> {
                var metadata = jdbc.queryForMap("""
                        SELECT v.project_ref, v.source_type, v.data_version, v.schema_version, v.catalog_version,
                               s.current_input_version_id, s.previous_input_version_id
                        FROM scsj_fact_input_version v JOIN scsj_fact_day_state s
                          ON s.account_id = v.account_id AND s.stat_date = v.stat_date
                        WHERE v.id = ?
                        """, versionId);
                if (!batch.projectRef().equals(metadata.get("project_ref"))
                        || !"MANUAL_HISTORY".equals(metadata.get("source_type"))
                        || !batch.dataVersion().equals(metadata.get("data_version"))
                        || !batch.schemaVersion().equals(metadata.get("schema_version"))
                        || !batch.catalogVersion().equals(metadata.get("catalog_version"))
                        || !(metadata.get("current_input_version_id") instanceof Number id) || id.longValue() != versionId
                        || metadata.get("previous_input_version_id") != null) throw rejected("HISTORY_READBACK_MISMATCH");
                StoredFactVersionSnapshot stored = jdbc.queryForObject("""
                        SELECT account_id, stat_date, content_hash, disposition, row_count,
                               manifest_path, manifest_size_bytes, manifest_sha256,
                               material_path, material_size_bytes, material_sha256
                        FROM scsj_fact_input_version WHERE id = ?
                        """, (r,n) -> new StoredFactVersionSnapshot(r.getString("account_id"),
                        r.getDate("stat_date").toLocalDate(), r.getString("content_hash"),
                        FactVersionDisposition.valueOf(r.getString("disposition")), r.getInt("row_count"),
                        r.getString("manifest_path"), r.getLong("manifest_size_bytes"), r.getString("manifest_sha256"),
                        r.getString("material_path"), r.getLong("material_size_bytes"), r.getString("material_sha256")), versionId);
                if (stored == null || stored.disposition() != FactVersionDisposition.CURRENT
                        || stored.rowCount() != batch.rows().size()
                        || !matches(batch.manifestEvidence(), stored.manifestPath(), stored.manifestSizeBytes(), stored.manifestSha256())
                        || !matches(batch.materialEvidence(), stored.materialPath(), stored.materialSizeBytes(), stored.materialSha256()))
                    throw rejected("HISTORY_READBACK_MISMATCH");
                List<StoredMaterialFactSnapshot> facts = jdbc.query("""
                        SELECT account_id, stat_date, material_id, source_row_number, raw_fact_json, row_sha256
                        FROM scsj_material_day_fact WHERE input_version_id = ? ORDER BY source_row_number
                        """, (r,n) -> new StoredMaterialFactSnapshot(r.getString("account_id"), r.getDate("stat_date").toLocalDate(),
                        r.getString("material_id"), r.getInt("source_row_number"), r.getString("raw_fact_json"), r.getString("row_sha256")), versionId);
                new StoredFactVersionVerifier().verify(batch, stored, facts);
                for (int i = 0; i < facts.size(); i++) {
                    var actual = facts.get(i); var expected = batch.rows().get(i);
                    if (!actual.rawFactJson().equals(expected.rawFactJson()) || !actual.materialId().equals(expected.materialId())
                            || actual.sourceRowNumber() != expected.sourceRowNumber() || !actual.rowSha256().equals(expected.rowSha256()))
                        throw rejected("HISTORY_READBACK_MISMATCH");
                }
                return null;
            });
            validateBatch(batch);
        } catch (Exception failure) { throw rejected("HISTORY_READBACK_FAILED"); }
    }

    private static boolean matches(InputFileEvidence e, String path, long size, String hash) {
        return e.absolutePath().equals(path) && e.sizeBytes() == size && e.sha256().equals(hash);
    }

    private static void validateBatch(PreparedFactBatch batch) {
        try {
            if (batch.sourceType() != FactSourceType.MANUAL_HISTORY || batch.catalogVersion() == null
                    || !batch.catalogVersion().startsWith("manual-history-upload-")) throw rejected("HISTORY_BATCH_INVALID");
            verifyFile(batch.manifestEvidence()); verifyFile(batch.materialEvidence());
            try (var lines = Files.newBufferedReader(Path.of(batch.materialEvidence().absolutePath()), StandardCharsets.UTF_8)) {
                for (var row : batch.rows()) {
                    if (!row.rawFactJson().equals(lines.readLine())) throw rejected("HISTORY_BATCH_INVALID");
                }
                if (lines.readLine() != null) throw rejected("HISTORY_BATCH_INVALID");
            }
            String content = "[" + String.join(",", batch.rows().stream().map(PreparedMaterialFact::rawFactJson).toList()) + "]";
            if (!hash(content.getBytes(StandardCharsets.UTF_8)).equals(batch.contentHash())) throw rejected("HISTORY_BATCH_INVALID");
            var ids = new HashSet<String>();
            for (int i=0;i<batch.rows().size();i++) {
                var row = batch.rows().get(i);
                if (row.sourceRowNumber()!=i+1 || !ids.add(row.materialId()) || row.materialId()==null || row.materialId().isBlank()
                        || !hash(row.rawFactJson().getBytes(StandardCharsets.UTF_8)).equals(row.rowSha256())) throw rejected("HISTORY_BATCH_INVALID");
            }
            // Validate JSON and the complete canonical content before any database write.
            new StoredFactVersionVerifier().verify(batch, new StoredFactVersionSnapshot(batch.accountId(), batch.statDate(),
                    batch.contentHash(), FactVersionDisposition.CURRENT, batch.rows().size(),
                    batch.manifestEvidence().absolutePath(), batch.manifestEvidence().sizeBytes(), batch.manifestEvidence().sha256(),
                    batch.materialEvidence().absolutePath(), batch.materialEvidence().sizeBytes(), batch.materialEvidence().sha256()),
                    batch.rows().stream().map(row -> new StoredMaterialFactSnapshot(batch.accountId(), batch.statDate(),
                            row.materialId(), row.sourceRowNumber(), row.rawFactJson(), row.rowSha256())).toList());
        } catch (Exception failure) { throw rejected("HISTORY_BATCH_INVALID"); }
    }

    private static void verifyFile(InputFileEvidence evidence) throws Exception {
        Path path = Path.of(evidence.absolutePath());
        if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || Files.size(path) != evidence.sizeBytes()) throw rejected("HISTORY_EVIDENCE_INVALID");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n=stream.read(buffer))!=-1) digest.update(buffer,0,n);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(evidence.sha256())) throw rejected("HISTORY_EVIDENCE_INVALID");
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private <T> T readOnly(Read<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) throw rejected("HISTORY_FRESH_CONNECTION_REQUIRED");
            boolean ro = connection.isReadOnly(); int isolation = connection.getTransactionIsolation();
            try {
                connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                return operation.run(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            } finally {
                connection.rollback(); connection.setAutoCommit(true);
                connection.setTransactionIsolation(isolation); connection.setReadOnly(ro);
            }
        } catch (Exception failure) { throw rejected("HISTORY_READ_ONLY_FAILED"); }
    }
    private interface Read<T> { T run(JdbcTemplate jdbc); }
    private static ReadbackVerificationException rejected(String code) { return new ReadbackVerificationException(code); }
}
