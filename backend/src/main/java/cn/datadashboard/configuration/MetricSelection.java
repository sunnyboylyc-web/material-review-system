package cn.datadashboard.configuration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Bounded selection from one immutable public catalog; no runtime catalog registration. */
public final class MetricSelection {
    public static final String CATALOG_ID = "material-33-v1";
    public static final String HISTORICAL_CATALOG = "uni-promotion-video-2026-09-29.1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] CATALOG_BYTES = load();
    private static final JsonNode CATALOG = JSON.readTree(CATALOG_BYTES);
    private static final List<String> ALL = strings(CATALOG.get("metrics"));
    private static final List<String> REQUIRED = strings(CATALOG.get("currentDashboardRequiredMetrics"));
    private static final List<String> DIMENSIONS = strings(CATALOG.get("dimensions"));

    private MetricSelection() { }

    public static List<String> allMetrics() { return ALL; }
    public static List<String> requiredMetrics() { return REQUIRED; }
    public static List<String> dimensions() { return DIMENSIONS; }

    public static List<String> validate(List<String> selected) {
        if (selected == null || selected.stream().anyMatch(value -> value == null || !ALL.contains(value))
                || new HashSet<>(selected).size() != selected.size() || !selected.containsAll(REQUIRED)) {
            throw new IllegalArgumentException("METRIC_SELECTION_INVALID");
        }
        return ALL.stream().filter(selected::contains).toList();
    }

    public static JsonNode profile(List<String> selected) {
        List<String> normalized = validate(selected);
        ObjectNode result = (ObjectNode) CATALOG.deepCopy();
        var metrics = result.putArray("metrics");
        normalized.forEach(metrics::add);
        for (JsonNode group : result.get("groups")) {
            List<String> groupMetrics = strings(group.get("metrics"));
            var target = ((ObjectNode) group).putArray("metrics");
            groupMetrics.stream().filter(normalized::contains).forEach(target::add);
        }
        return result;
    }

    public static String catalogSha256() { return sha(CATALOG_BYTES); }
    public static String selectionSha256(List<String> selected) {
        return sha(JSON.writeValueAsBytes(validate(selected)));
    }

    public static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) throw new IllegalArgumentException("METRIC_SELECTION_INVALID");
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isString()) throw new IllegalArgumentException("METRIC_SELECTION_INVALID");
            values.add(item.stringValue());
        }
        return List.copyOf(values);
    }

    private static byte[] load() {
        try (var stream = MetricSelection.class.getResourceAsStream("/configuration/material-33-v1.json")) {
            if (stream == null) throw new IllegalStateException();
            return stream.readAllBytes();
        } catch (Exception failure) { throw new IllegalStateException("METRIC_CATALOG_UNAVAILABLE"); }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException("METRIC_CATALOG_UNAVAILABLE"); }
    }
}
