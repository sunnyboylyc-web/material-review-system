package cn.datadashboard.query;

import cn.datadashboard.calculation.DashboardModels;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JdbcDashboardSnapshotReaderTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    private static final String ACCOUNT = "synthetic-account";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void decimalProjectionPreservesAllDigitsAndScientificNotation() {
        String raw = row("synthetic-1", "demo", "0.123456789012345678901234567890123456789", "1e-17");
        DashboardModels.Fact fact = JdbcDashboardSnapshotReader.decodeFact(raw, DAY, "synthetic-1", "MCP_AUTO");
        assertThat(fact.spend()).isEqualByComparingTo("0.123456789012345678901234567890123456789");
        assertThat(fact.gmv()).isEqualByComparingTo("0.00000000000000001");
        assertThat(fact.impressions()).isEqualByComparingTo("100");
        assertThat(fact.clicks()).isEqualByComparingTo("10");
        assertThat(fact.orders()).isEqualByComparingTo("2");
        assertThat(fact.createdDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void unknownAndMissingStayNullWhileZeroRemainsNumeric() {
        String raw = """
                {"material_id":"synthetic-1","stat_time_day":"2026-10-08",
                 "roi2_material_video_name":"UNKNOWN","material_create_time_v2":"--",
                 "stat_cost_for_roi2":0,"total_pay_order_gmv_for_roi2":"UNKNOWN",
                 "live_show_count_for_roi2_v2":null,"live_watch_count_for_roi2_v2":""}
                """;
        var fact = JdbcDashboardSnapshotReader.decodeFact(raw, DAY, "synthetic-1", "MANUAL_HISTORY");
        assertThat(fact.spend()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fact.gmv()).isNull();
        assertThat(fact.impressions()).isNull();
        assertThat(fact.clicks()).isNull();
        assertThat(fact.orders()).isNull();
        assertThat(fact.materialName()).isNull();
        assertThat(fact.createdDate()).isNull();
    }

    @Test
    void creationTimestampOffsetIsConvertedToShanghaiWhileNaiveTimestampKeepsLocalDate() {
        String raw = row("synthetic-1", "demo", "1", "2");
        ObjectNode object = (ObjectNode) JSON.readTree(raw);
        for (String timestamp : List.of("2026-09-30T17:00:00Z", "2026-10-01T01:00:00+08:00", "2026-10-01 01:00:00", "2026-10-01")) {
            object.put("material_create_time_v2", timestamp);
            assertThat(JdbcDashboardSnapshotReader.decodeFact(JSON.writeValueAsString(object), DAY,
                    "synthetic-1", "MCP_AUTO").createdDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        }
    }

    @Test
    void onlyExactManualAigcDisplaySentinelIsCanonicalized() {
        for (String name : List.of("AIGC动态创意素材集合", "AIGC动态创意视频素材集合")) {
            assertThat(JdbcDashboardSnapshotReader.decodeFact(row("-", name, "1", "2"), DAY, "-", "MANUAL_HISTORY")
                    .materialId()).isEqualTo("-2");
            assertThat(JdbcDashboardSnapshotReader.decodeFact(row("-2", name, "1", "2"), DAY, "-2", "MCP_AUTO")
                    .materialId()).isEqualTo("-2");
        }
        for (String name : List.of("AI ordinary material", "AIGC动态创意素材集合 extra", "UNKNOWN")) {
            assertThat(JdbcDashboardSnapshotReader.decodeFact(row("-", name, "1", "2"), DAY, "-", "MANUAL_HISTORY")
                    .materialId()).isEqualTo("-");
        }
        assertThat(JdbcDashboardSnapshotReader.decodeFact(row("synthetic-1", "AIGC动态创意素材集合", "1", "2"),
                DAY, "synthetic-1", "MANUAL_HISTORY").materialId()).isEqualTo("synthetic-1");
    }

    @Test
    void rejectsRawIdentityMismatchDuplicateKeysAndMalformedMetricsWithoutInputDisclosure() {
        String raw = row("synthetic-1", "demo", "1", "2");
        assertCode(() -> JdbcDashboardSnapshotReader.decodeFact(raw, DAY, "other", "MCP_AUTO"),
                "RAW_FACT_IDENTITY_MISMATCH");
        assertCode(() -> JdbcDashboardSnapshotReader.decodeFact(raw, DAY.minusDays(1), "synthetic-1", "MCP_AUTO"),
                "RAW_FACT_IDENTITY_MISMATCH");
        assertCode(() -> JdbcDashboardSnapshotReader.decodeFact(raw.replace("\"stat_cost_for_roi2\":1",
                "\"stat_cost_for_roi2\":1,\"stat_cost_for_roi2\":2"), DAY, "synthetic-1", "MCP_AUTO"),
                "FACT_JSON_INVALID");
        assertCode(() -> JdbcDashboardSnapshotReader.decodeFact(raw.replace("\"stat_cost_for_roi2\":1",
                "\"stat_cost_for_roi2\":\"secret-not-a-number\""), DAY, "synthetic-1", "MCP_AUTO"),
                "FACT_METRIC_INVALID");
    }

    @Test
    void readsCurrentAutoAndManualDaysInOneReadOnlySnapshotAndPreservesEmptyDay() throws Exception {
        String auto = row("synthetic-1", "demo", "0", "0");
        String manual = row("synthetic-2", "demo", "1e-07", "2.34").replace(DAY.toString(), DAY.plusDays(1).toString());
        List<Map<String, Object>> stored = new ArrayList<>();
        stored.add(storedRow(11, DAY, "MCP_AUTO", List.of(auto), 0));
        stored.add(storedRow(12, DAY.plusDays(1), "MANUAL_HISTORY", List.of(manual), 0));
        stored.add(storedRow(13, DAY.plusDays(2), "MANUAL_HISTORY", List.of(), -1));
        Harness harness = harness(stored);
        DashboardModels.Snapshot snapshot = harness.reader.read(ACCOUNT);
        assertThat(snapshot.accountId()).isEqualTo(ACCOUNT);
        assertThat(snapshot.days()).hasSize(3);
        assertThat(snapshot.facts()).hasSize(2);
        assertThat(snapshot.facts().get(1).spend()).isEqualByComparingTo("1e-07");
        verify(harness.connection).setReadOnly(true);
        verify(harness.connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(harness.connection).setAutoCommit(false);
        verify(harness.connection).rollback();
        verify(harness.connection).setAutoCommit(true);
        verify(harness.connection).setReadOnly(false);
        verify(harness.connection).close();
        verify(harness.query).setString(1, ACCOUNT);
        verify(harness.query).setFetchSize(Integer.MIN_VALUE);
        verify(harness.query, never()).executeUpdate();
    }

    @Test
    void freshnessUsesVerifiedCurrentEmptyMcpDayAndNeverLaterManualDay() throws Exception {
        Harness harness = harness(List.of(
                storedRow(11, DAY, "MCP_AUTO", List.of(), -1),
                storedRow(12, DAY.plusDays(3), "MANUAL_HISTORY", List.of(), -1)));
        var controller = new cn.datadashboard.api.DashboardController(
                cn.datadashboard.api.ProjectDirectory.single(ACCOUNT), harness.reader::read);
        assertThat(controller.freshness("sampleproject").latestMcpStatDate()).isEqualTo(DAY);
        verify(harness.connection).prepareStatement(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("v.id = s.current_input_version_id") && sql.contains("s.account_id = ?")));
        verify(harness.query, never()).executeUpdate();
    }

    @Test
    void noCurrentPointersReturnsNoCoveredDaysNotAnInventedEmptyDay() throws Exception {
        var result = harness(List.of()).reader.read(ACCOUNT);
        assertThat(result.days()).isEmpty();
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void rejectsTamperedRowsCountsSourceOrderingAndCrossAccountIdentity() throws Exception {
        String raw = row("synthetic-1", "demo", "1", "2");
        Map<String, Object> original = storedRow(11, DAY, "MANUAL_HISTORY", List.of(raw), 0);
        for (var mutation : List.of(
                Map.entry("row_sha256", "0".repeat(64)),
                Map.entry("content_hash", "0".repeat(64)),
                Map.entry("row_count", 2),
                Map.entry("source_row_number", 2),
                Map.entry("version_account", "other-account"),
                Map.entry("fact_account", "other-account"),
                Map.entry("disposition", "PREVIOUS"),
                Map.entry("previous_id", 11L),
                Map.entry("source_type", "UNVERIFIED_SOURCE"))) {
            Map<String, Object> changed = new HashMap<>(original);
            changed.put(mutation.getKey(), mutation.getValue());
            Harness harness = harness(List.of(changed));
            assertThatThrownBy(() -> harness.reader.read(ACCOUNT))
                    .isInstanceOf(JdbcDashboardSnapshotReader.SnapshotReadException.class)
                    .hasNoCause();
            verify(harness.connection).rollback();
        }
    }

    @Test
    void rejectsMissingPointedVersionAndNonemptyDeclaredEmptyPayload() throws Exception {
        Map<String, Object> missing = storedRow(11, DAY, "MANUAL_HISTORY", List.of(), -1);
        missing.remove("version_id");
        assertCode(() -> harness(missing).read(ACCOUNT), "CURRENT_VERSION_IDENTITY_MISMATCH");
        Map<String, Object> wrongCount = storedRow(11, DAY, "MANUAL_HISTORY", List.of(), -1);
        wrongCount.put("row_count", 1);
        assertCode(() -> harness(wrongCount).read(ACCOUNT), "FACT_COUNT_MISMATCH");
    }

    @Test
    void rejectsCanonicalAigcCollision() throws Exception {
        List<String> raw = List.of(row("-", "AIGC动态创意素材集合", "1", "2"),
                row("-2", "AIGC动态创意素材集合", "3", "4"));
        Harness harness = harness(List.of(storedRow(11, DAY, "MANUAL_HISTORY", raw, 0),
                storedRow(11, DAY, "MANUAL_HISTORY", raw, 1)));
        assertCode(() -> harness.reader.read(ACCOUNT), "DUPLICATE_CANONICAL_MATERIAL");
    }

    @Test
    void databaseErrorsAreSanitizedIncludingSuppressedCloseFailures() throws Exception {
        Harness harness = harness(List.of());
        when(harness.query.executeQuery()).thenThrow(new SQLException("secret connection details"));
        doThrow(new SQLException("secret close details")).when(harness.connection).close();
        assertThatThrownBy(() -> harness.reader.read(ACCOUNT)).satisfies(failure -> {
            assertThat(failure.getMessage()).isEqualTo("READ_ONLY_DATABASE_FAILED");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
        });
    }

    @Test
    void refusesNonReadOnlyServerSessionOrBorrowedTransaction() throws Exception {
        Harness harness = harness(List.of());
        when(harness.readOnlyRows.getBoolean(1)).thenReturn(false);
        assertCode(() -> harness.reader.read(ACCOUNT), "READ_ONLY_SESSION_REQUIRED");
        Harness borrowed = harness(List.of());
        when(borrowed.connection.getAutoCommit()).thenReturn(false);
        assertCode(() -> borrowed.reader.read(ACCOUNT), "FRESH_CONNECTION_REQUIRED");
        verify(borrowed.connection, never()).rollback();
    }

    @Test
    void inspectionSeparatesAvailableEmptyMissingAndIsolatedInOneReadOnlySnapshot() throws Exception {
        String raw = row("synthetic-1", "demo", "1", "2");
        Map<String, Object> current = storedRow(11, DAY, "MCP_AUTO", List.of(raw), 0);
        Map<String, Object> empty = storedRow(12, DAY.plusDays(1), "MANUAL_HISTORY", List.of(), -1);
        current.put("previous_id", 10L);
        var currentVersion = metadata(current);
        var emptyVersion = metadata(empty);
        var previous = additionalVersion(currentVersion, 10L, DAY, "PREVIOUS", "a".repeat(64));
        var isolated1 = additionalVersion(currentVersion, 21L, DAY, "ISOLATED", "b".repeat(64));
        var isolated2 = additionalVersion(currentVersion, 22L, DAY, "ISOLATED", "c".repeat(64));
        var missingIsolated = additionalVersion(currentVersion, 23L, DAY.plusDays(2), "ISOLATED", "d".repeat(64));
        Harness harness = harness(List.of(current, empty),
                List.of(pointer(DAY, 11L, 10L), pointer(DAY.plusDays(1), 12L, null), pointer(DAY.plusDays(2), null, null)),
                List.of(currentVersion, emptyVersion, previous, isolated1, isolated2, missingIsolated));
        var status = harness.reader.inspect(ACCOUNT, DAY.minusDays(1), DAY.plusDays(3));
        assertThat(status.schemaVersion()).isEqualTo("operations-status-1.2.0");
        assertThat(status.projectId()).isEqualTo("sampleproject");
        assertThat(status.validationScope()).isEqualTo("CURRENT_DB_FACT_HASHES");
        assertThat(status.sourceFilesRechecked()).isFalse();
        assertThat(status.collectionStatus()).isEqualTo("NOT_OBSERVED");
        assertThat(status.observedRange().first()).isEqualTo(DAY);
        assertThat(status.observedRange().last()).isEqualTo(DAY.plusDays(1));
        assertThat(status.summary().queriedDays()).isEqualTo(5);
        assertThat(status.summary().availableDays()).isEqualTo(2);
        assertThat(status.summary().emptyDays()).isEqualTo(1);
        assertThat(status.summary().missingDays()).isEqualTo(3);
        assertThat(status.summary().isolatedDays()).isEqualTo(2);
        assertThat(status.days()).extracting(day -> day.state())
                .containsExactly("MISSING", "AVAILABLE", "EMPTY", "MISSING", "MISSING");
        assertThat(status.days().get(1).previousVersionId()).isEqualTo(10L);
        assertThat(status.days().get(1).isolatedVersionCount()).isEqualTo(2);
        assertThat(status.days().get(2).currentVersion().rowCount()).isZero();
        assertThat(status.days().get(3).currentVersion()).isNull();
        assertThat(status.days().get(3).isolatedVersionCount()).isEqualTo(1);
        assertThat(status.days().get(1).currentVersion().updatedAt()).isEqualTo("2026-10-13T00:00:00Z");
        String serialized = JSON.writeValueAsString(status);
        assertThat(serialized).doesNotContain(ACCOUNT, "raw_fact_json", "material_id", "manifest_path", "material_path");
        verify(harness.connection).setReadOnly(true);
        verify(harness.connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(harness.connection).setAutoCommit(false);
        verify(harness.connection).rollback();
        verify(harness.connection).close();
    }

    @Test
    void inspectionWithoutCurrentEvidenceKeepsObservedRangeUnknownAndDoesNotInferFailure() throws Exception {
        var status = harness(List.of(), List.of(), List.of()).reader.inspect(ACCOUNT, DAY, DAY);
        assertThat(status.observedRange().first()).isNull();
        assertThat(status.observedRange().last()).isNull();
        assertThat(status.collectionStatus()).isEqualTo("NOT_OBSERVED");
        assertThat(status.summary().missingDays()).isEqualTo(1);
        assertThat(status.days().getFirst().state()).isEqualTo("MISSING");
    }

    @Test
    void inspectionValidatesRangeBeforeOpeningDatabase() throws Exception {
        Harness harness = harness(List.of());
        for (LocalDate[] range : List.of(
                new LocalDate[]{DAY, DAY.minusDays(1)},
                new LocalDate[]{DAY, DAY.plusDays(366)},
                new LocalDate[]{LocalDate.of(1899, 12, 31), DAY},
                new LocalDate[]{DAY, LocalDate.of(2201, 1, 1)},
                new LocalDate[]{null, DAY}, new LocalDate[]{DAY, null})) {
            assertThatThrownBy(() -> harness.reader.inspect(ACCOUNT, range[0], range[1]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("OPERATIONS_RANGE_INVALID");
        }
        verifyNoInteractions(harness.connection);
        var allowed = harness.reader.inspect(ACCOUNT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        assertThat(allowed.summary().queriedDays()).isEqualTo(366);
    }

    @Test
    void inspectionRejectsMetadataIdentityDispositionOrPointerMismatches() throws Exception {
        Map<String, Object> current = storedRow(11, DAY, "MANUAL_HISTORY", List.of(), -1);
        for (var mutation : List.of(
                Map.entry("version_account", "other-account"),
                Map.entry("version_date", java.sql.Date.valueOf(DAY.plusDays(1))),
                Map.entry("source_type", "MCP_AUTO"),
                Map.entry("content_hash", "a".repeat(64)),
                Map.entry("row_count", 1),
                Map.entry("disposition", "PREVIOUS"))) {
            Map<String, Object> version = metadata(current);
            version.put(mutation.getKey(), mutation.getValue());
            Harness harness = harness(List.of(current), List.of(pointer(DAY, 11L, null)), List.of(version));
            assertThatThrownBy(() -> harness.reader.inspect(ACCOUNT, DAY, DAY))
                    .isInstanceOf(JdbcDashboardSnapshotReader.SnapshotReadException.class).hasNoCause();
            verify(harness.connection).rollback();
        }
        var missingPrevious = harness(List.of(current), List.of(pointer(DAY, 11L, 10L)), List.of(metadata(current)));
        assertCode(() -> missingPrevious.reader.inspect(ACCOUNT, DAY, DAY), "OPERATIONS_VERSION_POINTER_MISMATCH");
        var orphanCurrent = harness(List.of(current), List.of(), List.of(metadata(current)));
        assertCode(() -> orphanCurrent.reader.inspect(ACCOUNT, DAY, DAY), "OPERATIONS_CURRENT_SNAPSHOT_MISMATCH");
    }

    @Test
    void inspectionDoesNotHideCurrentHashFailureAsMissingOrEmptySuccess() throws Exception {
        Map<String, Object> current = storedRow(11, DAY, "MANUAL_HISTORY", List.of(), -1);
        current.put("content_hash", "a".repeat(64));
        Harness harness = harness(List.of(current), List.of(pointer(DAY, 11L, null)), List.of(metadata(current)));
        assertCode(() -> harness.reader.inspect(ACCOUNT, DAY, DAY), "CONTENT_HASH_MISMATCH");
        verify(harness.connection).rollback();
    }

    @Test
    void inspectionRejectsAdditionalCurrentOrUnpointedPreviousMetadata() throws Exception {
        Map<String, Object> current = storedRow(11, DAY, "MANUAL_HISTORY", List.of(), -1);
        for (String disposition : List.of("CURRENT", "PREVIOUS")) {
            Map<String, Object> extra = additionalVersion(metadata(current), 10L, DAY, disposition, "a".repeat(64));
            Harness harness = harness(List.of(current), List.of(pointer(DAY, 11L, null)), List.of(metadata(current), extra));
            assertCode(() -> harness.reader.inspect(ACCOUNT, DAY, DAY), "OPERATIONS_VERSION_POINTER_MISMATCH");
        }
    }

    private static Map<String, Object> pointer(LocalDate day, Long current, Long previous) {
        Map<String, Object> values = new HashMap<>();
        values.put("state_account", ACCOUNT);
        values.put("state_date", java.sql.Date.valueOf(day));
        values.put("pointer_id", current);
        values.put("previous_id", previous);
        return values;
    }

    private static Map<String, Object> metadata(Map<String, Object> row) {
        Map<String, Object> metadata = new HashMap<>();
        for (String field : List.of("version_id", "version_account", "version_date", "source_type",
                "content_hash", "row_count", "disposition")) metadata.put(field, row.get(field));
        metadata.put("updated_at", java.sql.Timestamp.from(Instant.parse("2026-10-13T00:00:00Z")));
        return metadata;
    }

    private static Map<String, Object> additionalVersion(Map<String, Object> original, long id,
            LocalDate date, String disposition, String hash) {
        Map<String, Object> result = new HashMap<>(original);
        result.put("version_id", id);
        result.put("version_date", java.sql.Date.valueOf(date));
        result.put("disposition", disposition);
        result.put("content_hash", hash);
        return result;
    }

    private static String row(String id, String name, String spend, String gmv) {
        return "{\"material_id\":\"" + id + "\",\"stat_time_day\":\"2026-10-08\","
                + "\"roi2_material_video_name\":\"" + name + "\",\"material_create_time_v2\":\"2026-10-01 12:34:56\","
                + "\"stat_cost_for_roi2\":" + spend + ",\"total_pay_order_gmv_for_roi2\":" + gmv + ","
                + "\"live_show_count_for_roi2_v2\":100,\"live_watch_count_for_roi2_v2\":10,"
                + "\"total_pay_order_count_for_roi2\":2}";
    }

    private static Map<String, Object> storedRow(long version, LocalDate date, String source,
                                                 List<String> payload, int index) throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("state_account", ACCOUNT);
        values.put("state_date", java.sql.Date.valueOf(date));
        values.put("pointer_id", version);
        values.put("version_id", version);
        values.put("version_account", ACCOUNT);
        values.put("version_date", java.sql.Date.valueOf(date));
        values.put("source_type", source);
        String content = "[" + String.join(",", payload) + "]";
        if (source.equals("MCP_AUTO")) content = JSON.writeValueAsString(canonical(JSON.readTree(content)));
        values.put("content_hash", sha(content));
        values.put("disposition", "CURRENT");
        values.put("row_count", payload.size());
        if (index >= 0) {
            values.put("fact_id", (long) index + 1);
            values.put("fact_version", version);
            values.put("fact_account", ACCOUNT);
            values.put("fact_date", java.sql.Date.valueOf(date));
            values.put("material_id", JSON.readTree(payload.get(index)).get("material_id").stringValue());
            values.put("source_row_number", index + 1);
            values.put("raw_fact_json", payload.get(index));
            values.put("row_sha256", sha(payload.get(index)));
        }
        return values;
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            value.properties().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = JSON.createArrayNode();
            for (JsonNode child : value) sorted.add(canonical(child));
            return sorted;
        }
        return value.deepCopy();
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static JdbcDashboardSnapshotReader harness(Map<String, Object> stored) {
        try { return harness(List.of(stored)).reader; }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static Harness harness(List<Map<String, Object>> stored) throws Exception {
        return harness(stored, List.of(), List.of());
    }

    private static Harness harness(List<Map<String, Object>> stored, List<Map<String, Object>> pointers,
                                   List<Map<String, Object>> versions) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement query = mock(PreparedStatement.class);
        PreparedStatement readOnly = mock(PreparedStatement.class);
        PreparedStatement pointerQuery = mock(PreparedStatement.class);
        PreparedStatement metadataQuery = mock(PreparedStatement.class);
        ResultSet readOnlyRows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            if (sql.contains("@@transaction_read_only")) return readOnly;
            if (sql.contains("FROM scsj_fact_day_state WHERE")) return pointerQuery;
            if (sql.contains("FROM scsj_fact_input_version WHERE")) return metadataQuery;
            return query;
        });
        when(readOnly.executeQuery()).thenReturn(readOnlyRows);
        when(readOnlyRows.next()).thenReturn(true, false);
        when(readOnlyRows.getBoolean(1)).thenReturn(true);
        ResultSet factRows = resultSet(stored);
        ResultSet pointerRows = resultSet(pointers);
        ResultSet versionRows = resultSet(versions);
        when(query.executeQuery()).thenReturn(factRows);
        when(pointerQuery.executeQuery()).thenReturn(pointerRows);
        when(metadataQuery.executeQuery()).thenReturn(versionRows);
        return new Harness(new JdbcDashboardSnapshotReader(source), connection, query, readOnlyRows);
    }

    private static ResultSet resultSet(List<Map<String, Object>> stored) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger at = new AtomicInteger(-1);
        when(result.next()).thenAnswer(call -> at.incrementAndGet() < stored.size());
        when(result.getObject(anyString())).thenAnswer(call -> stored.get(at.get()).get(call.getArgument(0)));
        when(result.getObject(anyString(), eq(Long.class))).thenAnswer(call -> stored.get(at.get()).get(call.getArgument(0)));
        when(result.getString(anyString())).thenAnswer(call -> (String) stored.get(at.get()).get(call.getArgument(0)));
        when(result.getDate(anyString())).thenAnswer(call -> stored.get(at.get()).get(call.getArgument(0)));
        when(result.getTimestamp(anyString(), any(java.util.Calendar.class)))
                .thenAnswer(call -> stored.get(at.get()).get(call.getArgument(0)));
        when(result.getLong(anyString())).thenAnswer(call -> {
            Object value = stored.get(at.get()).get(call.getArgument(0));
            return value == null ? 0L : ((Number) value).longValue();
        });
        when(result.getInt(anyString())).thenAnswer(call -> {
            Object value = stored.get(at.get()).get(call.getArgument(0));
            return value == null ? 0 : ((Number) value).intValue();
        });
        return result;
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(JdbcDashboardSnapshotReader.SnapshotReadException.class)
                .hasMessage(code).hasNoCause();
    }

    private record Harness(JdbcDashboardSnapshotReader reader, Connection connection,
                           PreparedStatement query, ResultSet readOnlyRows) { }
}
