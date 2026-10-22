package cn.datadashboard.ingest.validation;

import cn.datadashboard.configuration.MetricSelection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;

/** Complete public-field fixtures; values are synthetic, never copied from business sources. */
public final class AutomaticFactFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private AutomaticFactFixtures() { }

    public static ObjectNode row(String id, String date) {
        ObjectNode row = JSON.createObjectNode().put("material_id", id).put("stat_time_day", date)
                .put("roi2_material_video_name", "synthetic material").put("roi2_material_video_type", "synthetic")
                .put("material_create_time_v2", date);
        MetricSelection.allMetrics().forEach(metric -> row.put(metric, "UNKNOWN"));
        return row.put("stat_cost_for_roi2", 0);
    }

    /** Add missing fixed groups without changing existing base pagination test structure. */
    public static void completeGroups(ObjectNode manifest, ObjectNode material) {
        manifest.put("fieldCatalogVersion", MetricSelection.HISTORICAL_CATALOG);
        material.put("catalogVersion", MetricSelection.HISTORICAL_CATALOG);
        ArrayNode pages = (ArrayNode) manifest.get("pages");
        var present = new HashSet<String>();
        for (JsonNode page : pages) present.add(page.get("group").stringValue());
        int count = material.get("rows").size();
        for (String name : java.util.List.of("base", "video", "assist", "ratio1", "ratio2")) {
            if (present.contains(name)) continue;
            ObjectNode page = pages.addObject().put("group", name).put("request_id", "synthetic-" + name)
                    .put("code", 0).put("page", 1).put("raw_rows", count);
            page.putObject("page_info").put("page", 1).put("page_size", name.equals("base") ? 100 : 200)
                    .put("total_number", count).put("total_page", 1).put("has_more", false);
        }
        int raw = 0;
        for (JsonNode page : pages) raw += page.get("raw_rows").intValue();
        manifest.put("rawRowCount", raw).put("pageCount", pages.size());
        ((ObjectNode) material.get("counts")).put("rawRowCount", raw).put("pageCount", pages.size());
    }
}
