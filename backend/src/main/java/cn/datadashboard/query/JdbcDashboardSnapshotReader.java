package cn.datadashboard.query;

import cn.datadashboard.calculation.DashboardModels;
import cn.datadashboard.operations.OperationsStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A verified, account-isolated current-version snapshot. This class has no write statements. */
public final class JdbcDashboardSnapshotReader {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> SOURCES = Set.of("MCP_AUTO", "MANUAL_HISTORY");
    private static final Set<String> AIGC_NAMES = Set.of("AIGC动态创意素材集合", "AIGC动态创意视频素材集合");
    // Content hashes must use the exact canonicalization used by the existing importer.
    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    // The calculation projection must not round JSON numeric tokens through double.
    private static final JsonMapper DECIMAL_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final String SNAPSHOT_SQL = """
            SELECT s.account_id AS state_account, s.stat_date AS state_date,
                   s.current_input_version_id AS pointer_id, s.previous_input_version_id AS previous_id,
                   v.id AS version_id, v.account_id AS version_account, v.stat_date AS version_date,
                   v.source_type, v.content_hash, v.disposition, v.row_count,
                   f.id AS fact_id, f.input_version_id AS fact_version,
                   f.account_id AS fact_account, f.stat_date AS fact_date,
                   f.material_id, f.source_row_number, f.raw_fact_json, f.row_sha256
            FROM scsj_fact_day_state s
            LEFT JOIN scsj_fact_input_version v ON v.id = s.current_input_version_id
            LEFT JOIN scsj_material_day_fact f ON f.input_version_id = v.id
            WHERE s.account_id = ? AND s.current_input_version_id IS NOT NULL
            ORDER BY s.stat_date, f.source_row_number
            """;
    private final DataSource dataSource;

    public JdbcDashboardSnapshotReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public DashboardModels.Snapshot read(String accountId) {
        return inReadOnlyTransaction(accountId, connection -> readSnapshot(connection, accountId));
    }

    /** Read-only maintenance diagnosis; it does not observe a collector or re-open source files. */
    public OperationsStatus inspect(String accountId, LocalDate start, LocalDate end) {
        if (start == null || end == null || start.getYear() < 1900 || end.getYear() > 2200
                || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 366) {
            throw new IllegalArgumentException("OPERATIONS_RANGE_INVALID");
        }
        return inReadOnlyTransaction(accountId, connection -> {
            DashboardModels.Snapshot snapshot = readSnapshot(connection, accountId);
            Map<LocalDate, DayPointers> pointers = readPointers(connection, accountId);
            Map<Long, MetadataVersion> versions = readMetadata(connection, accountId);
            validateMetadata(snapshot, pointers, versions);
            return operationsStatus(snapshot, pointers, versions, start, end);
        });
    }

    public DashboardModels.Snapshot readAccounts(String logicalAccount, List<String> accounts) {
        return inReadOnlyTransaction(logicalAccount, connection -> {
            var snapshots = new ArrayList<DashboardModels.Snapshot>();
            for (String account : accounts) snapshots.add(readSnapshot(connection, account));
            return mergeAccounts(logicalAccount, snapshots);
        });
    }

    /** Same-day sources are a conflict, never summed or silently selected. */
    public static DashboardModels.Snapshot mergeAccounts(String logicalAccount, List<DashboardModels.Snapshot> snapshots) {
        var days = new TreeMap<LocalDate, DashboardModels.DayEvidence>();
        var facts = new ArrayList<DashboardModels.Fact>();
        var accounts = new HashSet<String>();
        for (var snapshot : snapshots) {
            require(accounts.add(snapshot.accountId()), "DUPLICATE_SOURCE_ACCOUNT");
            for (var day : snapshot.days()) require(days.putIfAbsent(day.date(), day) == null, "DOUYIN_DUPLICATE_DAY");
            facts.addAll(snapshot.facts());
        }
        return new DashboardModels.Snapshot(logicalAccount, List.copyOf(days.values()), List.copyOf(facts));
    }

    public OperationsStatus inspectAccounts(String logicalAccount, List<String> accounts, LocalDate start, LocalDate end) {
        require(start != null && end != null && !end.isBefore(start)
                && ChronoUnit.DAYS.between(start, end) < 366, "OPERATIONS_RANGE_INVALID");
        return inReadOnlyTransaction(logicalAccount, connection -> {
            var snapshots = new ArrayList<DashboardModels.Snapshot>();
            Map<LocalDate, DayPointers> pointers = new TreeMap<>();
            Map<Long, MetadataVersion> versions = new HashMap<>();
            for (String account : accounts) {
                var snapshot = readSnapshot(connection, account);
                var sourcePointers = readPointers(connection, account);
                var sourceVersions = readMetadata(connection, account);
                validateMetadata(snapshot, sourcePointers, sourceVersions);
                snapshots.add(snapshot);
                for (var e : sourcePointers.entrySet())
                    require(pointers.putIfAbsent(e.getKey(), e.getValue()) == null, "DOUYIN_DUPLICATE_DAY");
                for (var e : sourceVersions.entrySet())
                    require(versions.putIfAbsent(e.getKey(), e.getValue()) == null, "DUPLICATE_VERSION");
            }
            return operationsStatus(mergeAccounts(logicalAccount, snapshots), pointers, versions, start, end);
        });
    }

    <T> T inReadOnlyTransaction(String accountId, SnapshotOperation<T> operation) {
        require(accountId != null && !accountId.isBlank() && accountId.length() <= 64, "ACCOUNT_REQUIRED");
        try (Connection connection = dataSource.getConnection()) {
            boolean initialReadOnly = connection.isReadOnly();
            boolean initialAutoCommit = connection.getAutoCommit();
            int initialIsolation = connection.getTransactionIsolation();
            // A fresh reader connection must not inherit somebody else's active transaction.
            require(initialAutoCommit, "FRESH_CONNECTION_REQUIRED");
            try {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                verifyReadOnlySession(connection);
                return operation.read(connection);
            } finally {
                // End the read snapshot, and restore the connection before returning it to a pool.
                connection.rollback();
                connection.setAutoCommit(initialAutoCommit);
                connection.setTransactionIsolation(initialIsolation);
                connection.setReadOnly(initialReadOnly);
            }
        } catch (SnapshotReadException failure) {
            // Drop any suppressed JDBC close exception, which can contain SQL or connection details.
            throw new SnapshotReadException(failure.code());
        } catch (Exception failure) {
            throw new SnapshotReadException("READ_ONLY_DATABASE_FAILED");
        }
    }

    private static Map<LocalDate, DayPointers> readPointers(Connection connection, String accountId) throws SQLException {
        Map<LocalDate, DayPointers> result = new TreeMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT account_id AS state_account, stat_date AS state_date,
                       current_input_version_id AS pointer_id, previous_input_version_id AS previous_id
                FROM scsj_fact_day_state WHERE account_id = ? ORDER BY stat_date
                """)) {
            query.setString(1, accountId);
            query.setQueryTimeout(60);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    LocalDate day = date(rows, "state_date");
                    Long current = rows.getObject("pointer_id", Long.class);
                    Long previous = rows.getObject("previous_id", Long.class);
                    require(accountId.equals(rows.getString("state_account"))
                                    && (current == null || current > 0)
                                    && (previous == null || previous > 0 && current != null && !previous.equals(current)),
                            "OPERATIONS_POINTER_INVALID");
                    require(result.putIfAbsent(day, new DayPointers(current, previous)) == null,
                            "OPERATIONS_DUPLICATE_DAY");
                }
            }
        }
        return result;
    }

    private static Map<Long, MetadataVersion> readMetadata(Connection connection, String accountId) throws SQLException {
        Map<Long, MetadataVersion> result = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id AS version_id, account_id AS version_account, stat_date AS version_date,
                       source_type, content_hash, disposition, row_count, updated_at
                FROM scsj_fact_input_version WHERE account_id = ? ORDER BY stat_date, id
                """)) {
            query.setString(1, accountId);
            query.setQueryTimeout(60);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("version_id");
                    LocalDate day = date(rows, "version_date");
                    String source = rows.getString("source_type");
                    String hash = rows.getString("content_hash");
                    String disposition = rows.getString("disposition");
                    int rowCount = rows.getInt("row_count");
                    java.sql.Timestamp updated = rows.getTimestamp("updated_at", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    require(id > 0 && accountId.equals(rows.getString("version_account"))
                                    && source != null && SOURCES.contains(source) && validHash(hash)
                                    && ("CURRENT".equals(disposition) || "PREVIOUS".equals(disposition) || "ISOLATED".equals(disposition))
                                    && rowCount >= 0 && updated != null,
                            "OPERATIONS_VERSION_INVALID");
                    require(result.putIfAbsent(id, new MetadataVersion(id, day, source, hash, disposition,
                                    rowCount, updated.toInstant().toString())) == null,
                            "OPERATIONS_DUPLICATE_VERSION");
                }
            }
        }
        return result;
    }

    private static void validateMetadata(DashboardModels.Snapshot snapshot, Map<LocalDate, DayPointers> pointers,
                                         Map<Long, MetadataVersion> versions) {
        Map<LocalDate, DashboardModels.DayEvidence> verified = new HashMap<>();
        Map<LocalDate, Integer> counts = new HashMap<>();
        snapshot.days().forEach(day -> verified.put(day.date(), day));
        snapshot.facts().forEach(fact -> counts.merge(fact.date(), 1, Integer::sum));
        for (Map.Entry<LocalDate, DayPointers> entry : pointers.entrySet()) {
            LocalDate day = entry.getKey();
            DayPointers state = entry.getValue();
            if (state.current != null) {
                requirePointer(versions.get(state.current), day, "CURRENT");
                MetadataVersion current = versions.get(state.current);
                DashboardModels.DayEvidence evidence = verified.get(day);
                require(evidence != null && evidence.versionId() == current.id
                                && evidence.sourceType().equals(current.source) && evidence.contentHash().equals(current.hash)
                                && counts.getOrDefault(day, 0) == current.rowCount,
                        "OPERATIONS_CURRENT_SNAPSHOT_MISMATCH");
            } else {
                require(!verified.containsKey(day), "OPERATIONS_CURRENT_SNAPSHOT_MISMATCH");
            }
            if (state.previous != null) requirePointer(versions.get(state.previous), day, "PREVIOUS");
        }
        for (DashboardModels.DayEvidence evidence : snapshot.days()) {
            require(pointers.containsKey(evidence.date()), "OPERATIONS_CURRENT_SNAPSHOT_MISMATCH");
        }
        for (MetadataVersion version : versions.values()) {
            DayPointers state = pointers.get(version.date);
            require(state != null, "OPERATIONS_VERSION_POINTER_MISMATCH");
            boolean valid = switch (version.disposition) {
                case "CURRENT" -> Long.valueOf(version.id).equals(state.current);
                case "PREVIOUS" -> Long.valueOf(version.id).equals(state.previous);
                case "ISOLATED" -> !Long.valueOf(version.id).equals(state.current)
                        && !Long.valueOf(version.id).equals(state.previous);
                default -> false;
            };
            require(valid, "OPERATIONS_VERSION_POINTER_MISMATCH");
        }
    }

    private static void requirePointer(MetadataVersion version, LocalDate day, String disposition) {
        require(version != null && version.date.equals(day) && disposition.equals(version.disposition),
                "OPERATIONS_VERSION_POINTER_MISMATCH");
    }

    private static OperationsStatus operationsStatus(DashboardModels.Snapshot snapshot,
            Map<LocalDate, DayPointers> pointers, Map<Long, MetadataVersion> versions, LocalDate start, LocalDate end) {
        Map<LocalDate, Integer> isolated = new HashMap<>();
        versions.values().stream().filter(version -> version.disposition.equals("ISOLATED"))
                .forEach(version -> isolated.merge(version.date, 1, Integer::sum));
        List<OperationsStatus.Day> days = new ArrayList<>();
        int available = 0, empty = 0, missing = 0, isolatedDays = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            DayPointers state = pointers.get(date);
            MetadataVersion current = state == null || state.current == null ? null : versions.get(state.current);
            int isolatedCount = isolated.getOrDefault(date, 0);
            if (isolatedCount > 0) isolatedDays++;
            String availability;
            OperationsStatus.Version publicVersion = null;
            if (current == null) {
                availability = "MISSING";
                missing++;
            } else {
                available++;
                availability = current.rowCount == 0 ? "EMPTY" : "AVAILABLE";
                if (current.rowCount == 0) empty++;
                publicVersion = new OperationsStatus.Version(current.id, current.source, current.hash,
                        current.rowCount, current.updatedAt);
            }
            days.add(new OperationsStatus.Day(date, availability, publicVersion,
                    state == null ? null : state.previous, isolatedCount));
        }
        LocalDate first = snapshot.days().stream().map(DashboardModels.DayEvidence::date).min(LocalDate::compareTo).orElse(null);
        LocalDate last = snapshot.days().stream().map(DashboardModels.DayEvidence::date).max(LocalDate::compareTo).orElse(null);
        List<OperationsStatus.MissingDetail> details = cn.datadashboard.operations.FactMissingDetails.inspect(snapshot, start, end);
        return new OperationsStatus("operations-status-1.2.0", "sampleproject", Instant.now().toString(),
                new OperationsStatus.DateRange(start, end), "CURRENT_DB_FACT_HASHES", false, "NOT_OBSERVED",
                new OperationsStatus.ObservedRange(first, last),
                new OperationsStatus.Summary(days.size(), available, empty, missing, isolatedDays), List.copyOf(days),
                List.copyOf(details.subList(0, Math.min(1000, details.size()))), details.size(), details.size() > 1000);
    }

    private static void verifyReadOnlySession(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT @@transaction_read_only");
             ResultSet rows = query.executeQuery()) {
            require(rows.next() && rows.getBoolean(1), "READ_ONLY_SESSION_REQUIRED");
        }
    }

    /** Lightweight current-version manifest; deliberately excludes the raw fact table. */
    static List<DerivedDayCache.Key> readManifest(Connection connection, String accountId) throws SQLException {
        List<DerivedDayCache.Key> result = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT s.account_id AS state_account, s.stat_date AS state_date,
                       s.current_input_version_id AS pointer_id, s.previous_input_version_id AS previous_id,
                       v.id AS version_id, v.account_id AS version_account, v.stat_date AS version_date,
                       v.source_type, v.content_hash, v.disposition, v.row_count
                FROM scsj_fact_day_state s
                LEFT JOIN scsj_fact_input_version v ON v.id = s.current_input_version_id
                WHERE s.account_id = ? AND s.current_input_version_id IS NOT NULL
                ORDER BY s.stat_date
                """)) {
            query.setString(1, accountId);
            query.setQueryTimeout(60);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Version version = readVersion(rows, accountId);
                    require(dates.add(version.date), "DUPLICATE_CURRENT_DAY");
                    result.add(new DerivedDayCache.Key(accountId, version.date, version.id,
                            version.hash, version.source, version.rowCount));
                }
            }
        }
        return List.copyOf(result);
    }

    static DashboardModels.Snapshot readDay(Connection connection, String accountId,
                                            DerivedDayCache.Key expected) throws SQLException {
        require(accountId.equals(expected.accountId()), "CURRENT_VERSION_IDENTITY_MISMATCH");
        DashboardModels.Snapshot snapshot = readSnapshot(connection, accountId, expected.date());
        require(snapshot.days().size() == 1 && snapshot.facts().size() == expected.rowCount(),
                "FACT_COUNT_MISMATCH");
        DashboardModels.DayEvidence evidence = snapshot.days().getFirst();
        require(evidence.date().equals(expected.date()) && evidence.versionId() == expected.versionId()
                        && evidence.contentHash().equals(expected.contentHash())
                        && evidence.sourceType().equals(expected.sourceType()),
                "CURRENT_VERSION_IDENTITY_MISMATCH");
        return snapshot;
    }

    static DashboardModels.Snapshot readSnapshot(Connection connection, String accountId) throws SQLException {
        return readSnapshot(connection, accountId, null);
    }

    private static DashboardModels.Snapshot readSnapshot(Connection connection, String accountId,
                                                         LocalDate onlyDay) throws SQLException {
        List<DashboardModels.DayEvidence> days = new ArrayList<>();
        List<DashboardModels.Fact> facts = new ArrayList<>();
        Set<LocalDate> completedDates = new HashSet<>();
        DayBuffer day = null;
        String sql = onlyDay == null ? SNAPSHOT_SQL : SNAPSHOT_SQL.replace("ORDER BY s.stat_date, f.source_row_number",
                "AND s.stat_date = ?\nORDER BY s.stat_date, f.source_row_number");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            // Connector/J otherwise buffers every raw JSON row before projection. Keep
            // the forward-only result streaming; day hashes and the repeatable-read
            // transaction still cover every row, with no truncation or cache reuse.
            query.setFetchSize(Integer.MIN_VALUE);
            query.setString(1, accountId);
            if (onlyDay != null) query.setDate(2, java.sql.Date.valueOf(onlyDay));
            query.setQueryTimeout(60);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Version version = readVersion(rows, accountId);
                    if (day == null || !day.version.equals(version)) {
                        if (day != null) day.finish(days, facts);
                        require(completedDates.add(version.date), "DUPLICATE_CURRENT_DAY");
                        day = new DayBuffer(version);
                    }
                    if (rows.getObject("fact_id") != null) day.add(rows, accountId);
                    else require(version.rowCount == 0, "FACT_COUNT_MISMATCH");
                }
            }
        }
        if (day != null) day.finish(days, facts);
        return new DashboardModels.Snapshot(accountId, List.copyOf(days), List.copyOf(facts));
    }

    private static Version readVersion(ResultSet rows, String accountId) throws SQLException {
        LocalDate date = date(rows, "state_date");
        long pointer = rows.getLong("pointer_id");
        Long previous = rows.getObject("previous_id", Long.class);
        require(rows.getObject("version_id") != null && pointer > 0
                        && pointer == rows.getLong("version_id")
                        && (previous == null || previous.longValue() != pointer)
                        && accountId.equals(rows.getString("state_account"))
                        && accountId.equals(rows.getString("version_account"))
                        && date.equals(date(rows, "version_date"))
                        && "CURRENT".equals(rows.getString("disposition")),
                "CURRENT_VERSION_IDENTITY_MISMATCH");
        String source = rows.getString("source_type");
        String hash = rows.getString("content_hash");
        int count = rows.getInt("row_count");
        require(SOURCES.contains(source) && validHash(hash) && count >= 0, "CURRENT_VERSION_INVALID");
        return new Version(pointer, date, source, hash, count);
    }

    private static final class DayBuffer {
        private final Version version;
        private final List<String> rawRows = new ArrayList<>();
        private final List<DashboardModels.Fact> facts = new ArrayList<>();
        private final Set<String> sourceIds = new HashSet<>();
        private final Set<String> canonicalIds = new HashSet<>();

        private DayBuffer(Version version) { this.version = version; }

        private void add(ResultSet rows, String accountId) throws SQLException {
            String materialId = rows.getString("material_id");
            String raw = rows.getString("raw_fact_json");
            String hash = rows.getString("row_sha256");
            require(rows.getLong("fact_version") == version.id
                            && accountId.equals(rows.getString("fact_account"))
                            && version.date.equals(date(rows, "fact_date"))
                            && materialId != null && !materialId.isBlank() && sourceIds.add(materialId)
                            && rows.getInt("source_row_number") == rawRows.size() + 1,
                    "FACT_IDENTITY_OR_ORDER_MISMATCH");
            require(raw != null && validHash(hash) && sha(raw).equals(hash), "FACT_BYTES_HASH_MISMATCH");
            DashboardModels.Fact fact = decodeFact(raw, version.date, materialId, version.source);
            require(canonicalIds.add(fact.materialId()), "DUPLICATE_CANONICAL_MATERIAL");
            rawRows.add(raw);
            facts.add(fact);
        }

        private void finish(List<DashboardModels.DayEvidence> days, List<DashboardModels.Fact> target) {
            require(rawRows.size() == version.rowCount, "FACT_COUNT_MISMATCH");
            String reconstructed;
            if ("MANUAL_HISTORY".equals(version.source)) {
                // Python-canonical original tokens, including scientific notation, remain byte exact.
                reconstructed = sha("[" + String.join(",", rawRows) + "]");
            } else {
                ArrayNode array = CANONICAL_JSON.createArrayNode();
                for (String raw : rawRows) array.add(parseObject(CANONICAL_JSON, raw));
                reconstructed = sha(CANONICAL_JSON.writeValueAsString(canonical(array)));
            }
            require(version.hash.equals(reconstructed), "CONTENT_HASH_MISMATCH");
            days.add(new DashboardModels.DayEvidence(version.date, version.id, version.hash, version.source));
            target.addAll(facts);
        }
    }

    /** Projection only; never edits raw facts. All exceptions expose fixed codes, not source values. */
    public static DashboardModels.Fact decodeFact(String raw, LocalDate date, String materialId, String sourceType) {
        try {
            require(date != null && materialId != null && !materialId.isBlank() && SOURCES.contains(sourceType),
                    "FACT_ARGUMENT_INVALID");
            JsonNode row = parseObject(DECIMAL_JSON, raw);
            require(materialId.equals(requiredText(row, "material_id"))
                            && date.toString().equals(requiredText(row, "stat_time_day")),
                    "RAW_FACT_IDENTITY_MISMATCH");
            String name = optionalText(row.get("roi2_material_video_name"));
            String canonicalId = materialId;
            if ("MANUAL_HISTORY".equals(sourceType) && "-".equals(materialId)
                    && name != null && AIGC_NAMES.contains(name)) {
                canonicalId = "-2";
            }
            return new DashboardModels.Fact(date, canonicalId, name,
                    creationDate(row.get("material_create_time_v2")),
                    metric(row, "stat_cost_for_roi2"), metric(row, "total_pay_order_gmv_for_roi2"),
                    metric(row, "live_show_count_for_roi2_v2"), metric(row, "live_watch_count_for_roi2_v2"),
                    metric(row, "total_pay_order_count_for_roi2"));
        } catch (SnapshotReadException failure) {
            throw new SnapshotReadException(failure.code());
        } catch (Exception failure) {
            throw new SnapshotReadException("FACT_PROJECTION_FAILED");
        }
    }

    private static BigDecimal metric(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isTextual() && (value.stringValue().isBlank() || "UNKNOWN".equals(value.stringValue()))) return null;
        require(value.isNumber() || value.isTextual(), "FACT_METRIC_INVALID");
        try {
            return new BigDecimal(value.isTextual() ? value.stringValue() : value.asString());
        } catch (NumberFormatException failure) {
            throw new SnapshotReadException("FACT_METRIC_INVALID");
        }
    }

    private static LocalDate creationDate(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        String text = value.stringValue();
        try { return LocalDate.parse(text); } catch (DateTimeParseException ignored) { }
        String normalized = text.replace(' ', 'T');
        try { return LocalDateTime.parse(normalized).toLocalDate(); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(normalized).atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(); }
        catch (DateTimeParseException ignored) { }
        return null;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && !value.stringValue().isBlank(), "FACT_IDENTITY_INVALID");
        return value.stringValue();
    }

    private static String optionalText(JsonNode value) {
        if (value == null || value.isNull()) return null;
        require(value.isTextual(), "FACT_NAME_INVALID");
        String text = value.stringValue();
        return text.isBlank() || "UNKNOWN".equals(text) ? null : text;
    }

    private static JsonNode parseObject(JsonMapper mapper, String raw) {
        try {
            JsonNode parsed = mapper.readTree(raw);
            require(parsed != null && parsed.isObject(), "FACT_JSON_INVALID");
            return parsed;
        } catch (Exception failure) {
            throw new SnapshotReadException("FACT_JSON_INVALID");
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = CANONICAL_JSON.createObjectNode();
            node.properties().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = CANONICAL_JSON.createArrayNode();
            for (JsonNode child : node) sorted.add(canonical(child));
            return sorted;
        }
        return node.deepCopy();
    }

    private static LocalDate date(ResultSet rows, String column) throws SQLException {
        java.sql.Date value = rows.getDate(column);
        require(value != null, "STORED_DATE_MISSING");
        return value.toLocalDate();
    }

    private static boolean validHash(String hash) { return hash != null && SHA256.matcher(hash).matches(); }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new SnapshotReadException("HASH_UNAVAILABLE");
        }
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new SnapshotReadException(code);
    }

    private record Version(long id, LocalDate date, String source, String hash, int rowCount) { }
    private record DayPointers(Long current, Long previous) { }
    private record MetadataVersion(long id, LocalDate date, String source, String hash,
                                   String disposition, int rowCount, String updatedAt) { }
    @FunctionalInterface
    interface SnapshotOperation<T> {
        T read(Connection connection) throws SQLException;
    }

    public static final class SnapshotReadException extends RuntimeException {
        private final String code;
        private SnapshotReadException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
