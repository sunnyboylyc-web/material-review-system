package cn.datadashboard.ingest.persistence;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

@Repository
public class JdbcFactImportStore implements FactImportStore {

    private static final String INSERT_VERSION_SQL = """
            INSERT INTO scsj_fact_input_version (
                account_id, stat_date, project_ref, source_type, data_version, content_hash,
                schema_version, catalog_version,
                manifest_path, manifest_size_bytes, manifest_sha256,
                material_path, material_size_bytes, material_sha256,
                disposition, row_count, run_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FACT_SQL = """
            INSERT INTO scsj_material_day_fact (
                input_version_id, account_id, stat_date, material_id,
                source_row_number, raw_fact_json, row_sha256
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final VersionPlacementPolicy placementPolicy;
    private final StoredFactVersionVerifier storedVersionVerifier;

    JdbcFactImportStore(
            JdbcTemplate jdbcTemplate,
            VersionPlacementPolicy placementPolicy,
            StoredFactVersionVerifier storedVersionVerifier
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.placementPolicy = placementPolicy;
        this.storedVersionVerifier = storedVersionVerifier;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FactStoreResult importAtomically(PreparedFactBatch batch) {
        DayVersionPointers pointers = lockAccountDay(batch.accountId(), batch.statDate());
        ExistingVersion existing = findExistingVersion(batch);
        if (existing != null) {
            verifyStoredVersion(batch, existing);
            verifyExistingPlacement(pointers, existing);
            return new FactStoreResult(
                    existing.id(),
                    existing.disposition(),
                    FactStoreOutcome.IDEMPOTENT,
                    existing.rowCount());
        }

        VersionPlacement placement = placementPolicy.decide(pointers);
        FactVersionDisposition newDisposition = placement == VersionPlacement.ISOLATED
                ? FactVersionDisposition.ISOLATED
                : FactVersionDisposition.CURRENT;
        long versionId = insertVersion(batch, newDisposition);
        insertFacts(versionId, batch);
        verifyReadback(batch, versionId, newDisposition, batch.rows().size());
        applyPlacement(batch, pointers, placement, versionId);
        verifyPlacementReadback(batch, pointers, placement, versionId);

        return new FactStoreResult(
                versionId,
                newDisposition,
                FactStoreOutcome.INSERTED,
                batch.rows().size());
    }

    private DayVersionPointers lockAccountDay(String accountId, LocalDate statDate) {
        jdbcTemplate.update(
                """
                INSERT INTO scsj_fact_day_state (account_id, stat_date)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE account_id = account_id
                """,
                accountId,
                Date.valueOf(statDate));
        return jdbcTemplate.queryForObject(
                """
                SELECT current_input_version_id, previous_input_version_id
                FROM scsj_fact_day_state
                WHERE account_id = ? AND stat_date = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new DayVersionPointers(
                        resultSet.getObject("current_input_version_id", Long.class),
                        resultSet.getObject("previous_input_version_id", Long.class)),
                accountId,
                Date.valueOf(statDate));
    }

    /** New upload path only: caller must provide an active transaction. Never replaces a day. */
    FactStoreResult importNewHistoryDay(PreparedFactBatch batch, LocalDate verifiedFirstMcpDate) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || batch.sourceType() != FactSourceType.MANUAL_HISTORY
                || batch.catalogVersion() == null || !batch.catalogVersion().startsWith("manual-history-upload-")) {
            throw new ReadbackVerificationException("HISTORY_IMPORT_CONTEXT_INVALID");
        }
        DayVersionPointers pointers = lockAccountDay(batch.accountId(), batch.statDate());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scsj_fact_input_version WHERE account_id = ? AND stat_date = ?",
                Integer.class, batch.accountId(), Date.valueOf(batch.statDate()));
        if (count == null || count != 0 || pointers.currentVersionId() != null || pointers.previousVersionId() != null) {
            throw new ReadbackVerificationException("HISTORY_DAY_ALREADY_EXISTS");
        }
        Date first = jdbcTemplate.queryForObject("""
                SELECT MIN(v.stat_date) FROM scsj_fact_input_version v
                JOIN scsj_fact_day_state s ON s.current_input_version_id = v.id
                  AND s.account_id = v.account_id AND s.stat_date = v.stat_date
                WHERE v.account_id = ? AND v.source_type = 'MCP_AUTO' AND v.disposition = 'CURRENT'
                """, Date.class, batch.accountId());
        LocalDate actualFirst = first == null ? null : first.toLocalDate();
        LocalDate cutoff = actualFirst == null ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")) : actualFirst;
        if (!java.util.Objects.equals(actualFirst, verifiedFirstMcpDate)
                || !batch.statDate().isBefore(cutoff)) {
            throw new ReadbackVerificationException("HISTORY_MCP_CUTOFF_CHANGED_OR_INVALID");
        }
        long id = insertVersion(batch, FactVersionDisposition.CURRENT);
        insertFacts(id, batch);
        verifyReadback(batch, id, FactVersionDisposition.CURRENT, batch.rows().size());
        applyPlacement(batch, pointers, VersionPlacement.FIRST_CURRENT, id);
        verifyPlacementReadback(batch, pointers, VersionPlacement.FIRST_CURRENT, id);
        return new FactStoreResult(id, FactVersionDisposition.CURRENT, FactStoreOutcome.INSERTED, batch.rows().size());
    }

    private ExistingVersion findExistingVersion(PreparedFactBatch batch) {
        List<ExistingVersion> matches = jdbcTemplate.query(
                """
                SELECT id, disposition, row_count
                FROM scsj_fact_input_version
                WHERE account_id = ? AND stat_date = ? AND content_hash = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new ExistingVersion(
                        resultSet.getLong("id"),
                        FactVersionDisposition.valueOf(resultSet.getString("disposition")),
                        resultSet.getInt("row_count")),
                batch.accountId(),
                Date.valueOf(batch.statDate()),
                batch.contentHash());
        if (matches.size() > 1) {
            throw new ReadbackVerificationException("Unique account/day/content hash invariant failed");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private long insertVersion(PreparedFactBatch batch, FactVersionDisposition disposition) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    INSERT_VERSION_SQL,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, batch.accountId());
            statement.setDate(2, Date.valueOf(batch.statDate()));
            statement.setString(3, batch.projectRef());
            statement.setString(4, batch.sourceType().name());
            statement.setString(5, batch.dataVersion());
            statement.setString(6, batch.contentHash());
            statement.setString(7, batch.schemaVersion());
            statement.setString(8, batch.catalogVersion());
            statement.setString(9, batch.manifestEvidence().absolutePath());
            statement.setLong(10, batch.manifestEvidence().sizeBytes());
            statement.setString(11, batch.manifestEvidence().sha256());
            statement.setString(12, batch.materialEvidence().absolutePath());
            statement.setLong(13, batch.materialEvidence().sizeBytes());
            statement.setString(14, batch.materialEvidence().sha256());
            statement.setString(15, disposition.name());
            statement.setInt(16, batch.rows().size());
            statement.setString(17, batch.runId());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (inserted != 1 || key == null) {
            throw new ReadbackVerificationException("Input version insert did not return exactly one key");
        }
        return key.longValue();
    }

    private void insertFacts(long versionId, PreparedFactBatch batch) {
        jdbcTemplate.batchUpdate(INSERT_FACT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                PreparedMaterialFact row = batch.rows().get(index);
                statement.setLong(1, versionId);
                statement.setString(2, batch.accountId());
                statement.setDate(3, Date.valueOf(batch.statDate()));
                statement.setString(4, row.materialId());
                statement.setInt(5, row.sourceRowNumber());
                statement.setString(6, row.rawFactJson());
                statement.setString(7, row.rowSha256());
            }

            @Override
            public int getBatchSize() {
                return batch.rows().size();
            }
        });
    }

    private void verifyReadback(
            PreparedFactBatch batch,
            long versionId,
            FactVersionDisposition disposition,
            int expectedRowCount
    ) {
        ReadbackVersion version = jdbcTemplate.queryForObject(
                """
                SELECT account_id, stat_date, content_hash, disposition, row_count,
                       manifest_sha256, material_sha256
                FROM scsj_fact_input_version
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new ReadbackVersion(
                        resultSet.getString("account_id"),
                        resultSet.getDate("stat_date").toLocalDate(),
                        resultSet.getString("content_hash"),
                        FactVersionDisposition.valueOf(resultSet.getString("disposition")),
                        resultSet.getInt("row_count"),
                        resultSet.getString("manifest_sha256"),
                        resultSet.getString("material_sha256")),
                versionId);
        if (version == null
                || !batch.accountId().equals(version.accountId())
                || !batch.statDate().equals(version.statDate())
                || !batch.contentHash().equals(version.contentHash())
                || disposition != version.disposition()
                || expectedRowCount != version.rowCount()
                || !batch.manifestEvidence().sha256().equals(version.manifestSha256())
                || !batch.materialEvidence().sha256().equals(version.materialSha256())) {
            throw new ReadbackVerificationException("Input version readback did not match the prepared batch");
        }

        List<ReadbackFact> facts = jdbcTemplate.query(
                """
                SELECT material_id, source_row_number, raw_fact_json, row_sha256
                FROM scsj_material_day_fact
                WHERE input_version_id = ?
                ORDER BY source_row_number
                """,
                (resultSet, rowNumber) -> new ReadbackFact(
                        resultSet.getString("material_id"),
                        resultSet.getInt("source_row_number"),
                        resultSet.getString("raw_fact_json"),
                        resultSet.getString("row_sha256")),
                versionId);
        if (facts.size() != batch.rows().size()) {
            throw new ReadbackVerificationException("Fact row count readback did not match the prepared batch");
        }
        for (int index = 0; index < facts.size(); index++) {
            ReadbackFact actual = facts.get(index);
            PreparedMaterialFact expected = batch.rows().get(index);
            String actualPayloadHash = sha256(actual.rawFactJson());
            if (!expected.materialId().equals(actual.materialId())
                    || expected.sourceRowNumber() != actual.sourceRowNumber()
                    || !expected.rowSha256().equals(actual.rowSha256())
                    || !expected.rowSha256().equals(actualPayloadHash)) {
                throw new ReadbackVerificationException("Fact row readback did not match the prepared batch");
            }
        }
    }

    private void verifyStoredVersion(PreparedFactBatch incoming, ExistingVersion existing) {
        StoredFactVersionSnapshot stored = jdbcTemplate.queryForObject(
                """
                SELECT account_id, stat_date, content_hash, disposition, row_count,
                       manifest_path, manifest_size_bytes, manifest_sha256,
                       material_path, material_size_bytes, material_sha256
                FROM scsj_fact_input_version
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new StoredFactVersionSnapshot(
                        resultSet.getString("account_id"),
                        resultSet.getDate("stat_date").toLocalDate(),
                        resultSet.getString("content_hash"),
                        FactVersionDisposition.valueOf(resultSet.getString("disposition")),
                        resultSet.getInt("row_count"),
                        resultSet.getString("manifest_path"),
                        resultSet.getLong("manifest_size_bytes"),
                        resultSet.getString("manifest_sha256"),
                        resultSet.getString("material_path"),
                        resultSet.getLong("material_size_bytes"),
                        resultSet.getString("material_sha256")),
                existing.id());
        if (stored == null
                || stored.disposition() != existing.disposition()
                || stored.rowCount() != existing.rowCount()) {
            throw new ReadbackVerificationException("Stored version lookup changed during idempotent readback");
        }
        List<StoredMaterialFactSnapshot> storedFacts = jdbcTemplate.query(
                """
                SELECT account_id, stat_date, material_id, source_row_number,
                       raw_fact_json, row_sha256
                FROM scsj_material_day_fact
                WHERE input_version_id = ?
                ORDER BY source_row_number
                """,
                (resultSet, rowNumber) -> new StoredMaterialFactSnapshot(
                        resultSet.getString("account_id"),
                        resultSet.getDate("stat_date").toLocalDate(),
                        resultSet.getString("material_id"),
                        resultSet.getInt("source_row_number"),
                        resultSet.getString("raw_fact_json"),
                        resultSet.getString("row_sha256")),
                existing.id());
        storedVersionVerifier.verify(incoming, stored, storedFacts);
    }

    private void applyPlacement(
            PreparedFactBatch batch,
            DayVersionPointers pointers,
            VersionPlacement placement,
            long newVersionId
    ) {
        int updated;
        if (placement == VersionPlacement.FIRST_CURRENT) {
            updated = jdbcTemplate.update(
                    """
                    UPDATE scsj_fact_day_state
                    SET current_input_version_id = ?
                    WHERE account_id = ? AND stat_date = ?
                      AND current_input_version_id IS NULL
                      AND previous_input_version_id IS NULL
                    """,
                    newVersionId,
                    batch.accountId(),
                    Date.valueOf(batch.statDate()));
            requireSingleUpdate(updated, "Initial current version pointer update failed");
            return;
        }
        if (placement == VersionPlacement.REPLACE_CURRENT) {
            updated = jdbcTemplate.update(
                    """
                    UPDATE scsj_fact_input_version
                    SET disposition = 'PREVIOUS'
                    WHERE id = ? AND disposition = 'CURRENT'
                    """,
                    pointers.currentVersionId());
            requireSingleUpdate(updated, "Previous version disposition update failed");
            updated = jdbcTemplate.update(
                    """
                    UPDATE scsj_fact_day_state
                    SET current_input_version_id = ?, previous_input_version_id = ?
                    WHERE account_id = ? AND stat_date = ?
                      AND current_input_version_id = ?
                      AND previous_input_version_id IS NULL
                    """,
                    newVersionId,
                    pointers.currentVersionId(),
                    batch.accountId(),
                    Date.valueOf(batch.statDate()),
                    pointers.currentVersionId());
            requireSingleUpdate(updated, "Current and previous version pointer update failed");
        }
    }

    private void verifyExistingPlacement(DayVersionPointers pointers, ExistingVersion existing) {
        boolean valid = switch (existing.disposition()) {
            case CURRENT -> Long.valueOf(existing.id()).equals(pointers.currentVersionId());
            case PREVIOUS -> Long.valueOf(existing.id()).equals(pointers.previousVersionId());
            case ISOLATED -> !Long.valueOf(existing.id()).equals(pointers.currentVersionId())
                    && !Long.valueOf(existing.id()).equals(pointers.previousVersionId());
        };
        if (!valid) {
            throw new ReadbackVerificationException("Existing version disposition does not match account-day pointers");
        }
    }

    private void verifyPlacementReadback(
            PreparedFactBatch batch,
            DayVersionPointers before,
            VersionPlacement placement,
            long newVersionId
    ) {
        DayVersionPointers after = jdbcTemplate.queryForObject(
                """
                SELECT current_input_version_id, previous_input_version_id
                FROM scsj_fact_day_state
                WHERE account_id = ? AND stat_date = ?
                """,
                (resultSet, rowNumber) -> new DayVersionPointers(
                        resultSet.getObject("current_input_version_id", Long.class),
                        resultSet.getObject("previous_input_version_id", Long.class)),
                batch.accountId(),
                Date.valueOf(batch.statDate()));
        boolean pointersMatch = switch (placement) {
            case FIRST_CURRENT -> after != null
                    && Long.valueOf(newVersionId).equals(after.currentVersionId())
                    && after.previousVersionId() == null;
            case REPLACE_CURRENT -> after != null
                    && Long.valueOf(newVersionId).equals(after.currentVersionId())
                    && before.currentVersionId().equals(after.previousVersionId());
            case ISOLATED -> before.equals(after);
        };
        if (!pointersMatch || readDisposition(newVersionId) != (placement == VersionPlacement.ISOLATED
                ? FactVersionDisposition.ISOLATED
                : FactVersionDisposition.CURRENT)) {
            throw new ReadbackVerificationException("Account-day version placement readback failed");
        }
        if (placement == VersionPlacement.REPLACE_CURRENT
                && readDisposition(before.currentVersionId()) != FactVersionDisposition.PREVIOUS) {
            throw new ReadbackVerificationException("Previous version disposition readback failed");
        }
    }

    private FactVersionDisposition readDisposition(long versionId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT disposition FROM scsj_fact_input_version WHERE id = ?",
                String.class,
                versionId);
        if (value == null) {
            throw new ReadbackVerificationException("Input version disposition is missing");
        }
        return FactVersionDisposition.valueOf(value);
    }

    private static void requireSingleUpdate(int updated, String message) {
        if (updated != 1) {
            throw new ReadbackVerificationException(message);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ExistingVersion(
            long id,
            FactVersionDisposition disposition,
            int rowCount
    ) {
    }

    private record ReadbackVersion(
            String accountId,
            LocalDate statDate,
            String contentHash,
            FactVersionDisposition disposition,
            int rowCount,
            String manifestSha256,
            String materialSha256
    ) {
    }

    private record ReadbackFact(
            String materialId,
            int sourceRowNumber,
            String rawFactJson,
            String rowSha256
    ) {
    }
}
