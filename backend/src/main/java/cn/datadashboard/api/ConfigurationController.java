package cn.datadashboard.api;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.configuration.MetricSelection;
import cn.datadashboard.configuration.AnchorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local development configuration only; does not authorize or execute collection. */
@RestController
@RequestMapping("/api/operations/configuration")
public final class ConfigurationController {
    private static final Set<String> HOSTS = Set.of("127.0.0.1:5180", "127.0.0.1:18080");
    private static final Set<String> ORIGINS = Set.of("http://127.0.0.1:5180", "http://127.0.0.1:18080");
    private final ProjectConfigurationStore store;
    private final boolean writable;
    private final AnchorResolver resolver;
    private java.util.function.Predicate<String> unusedAccount = account -> false;
    public ConfigurationController withUnusedAccountCheck(java.util.function.Predicate<String> check) { unusedAccount = check; return this; }
    private cn.datadashboard.security.TrialRequestGuard trialGuard;
    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ConfigurationController(ProjectConfigurationStore store, boolean writable) {
        this(store, writable, new AnchorResolver((a, p, n) -> { throw new AnchorResolver.Failure("ANCHOR_CONNECTION_UNAVAILABLE"); }));
    }
    public ConfigurationController(ProjectConfigurationStore store, boolean writable, AnchorResolver resolver) {
        this.store = store; this.writable = writable; this.resolver = resolver;
    }
    public ConfigurationController(ProjectConfigurationStore store, boolean writable, AnchorResolver resolver,
            cn.datadashboard.security.TrialRequestGuard guard) {
        this(store, writable, resolver); this.trialGuard = java.util.Objects.requireNonNull(guard);
    }
    @PostMapping(value = "/resolve-anchor", consumes = "application/json")
    public Object resolveAnchor(@RequestBody String body, HttpServletRequest request) {
        requireLocal(request, true);
        var node = parse(body, Set.of("advertiserId", "publicDouyinId"));
        if (node.size() != 2) throw new IllegalArgumentException();
        return resolver.resolve(requiredText(node, "advertiserId"), requiredText(node, "publicDouyinId"));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> current(HttpServletRequest request) {
        requireLocal(request, false);
        var snapshot = store.snapshot();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(currentBody(snapshot));
    }

    private Map<String, Object> currentBody(ProjectConfigurationStore.Snapshot snapshot) {
        var body = new java.util.LinkedHashMap<String, Object>(Map.of(
                "schemaVersion", "project-configuration-v1", "revision", snapshot.revision(),
                "writeEnabled", writable, "collectorExecution", "DISABLED",
                "metricProfile", "material-33-v1", "metricChangesEnabled", true, "themeChangesEnabled", true,
                "enabledMetrics", snapshot.document().enabledMetrics(),
                "metricCatalog", metricCatalog(), "projects", snapshot.document().projects()));
        body.put("collectingProjectIds", store.collectionBusy() ? snapshot.document().projects().stream().map(ProjectConfigurationStore.Project::id).toList() : List.of());
        return body;
    }

    @PostMapping(value = "/preflight", consumes = "application/json")
    public Object preflight(@RequestBody String body, HttpServletRequest request) {
        requireLocal(request, true);
        var node = parse(body, Set.of("expectedRevision", "projects"));
        verifyChangedIdentity(node);
        if (node.has("enabledMetrics")) return store.preflight(requiredText(node, "expectedRevision"),
                ProjectConfigurationStore.parseProjects(node.get("projects")), metrics(node));
        return store.preflight(requiredText(node, "expectedRevision"),
                ProjectConfigurationStore.parseProjects(node.get("projects")));
    }

    @PostMapping(value = "/apply", consumes = "application/json")
    public Object apply(@RequestBody String body, HttpServletRequest request) {
        requireLocal(request, true);
        var node = parse(body, Set.of("expectedRevision", "projects", "candidateHash"));
        verifyChangedIdentity(node);
        if (trialGuard != null) return store.applyAs(requiredText(node, "expectedRevision"),
                ProjectConfigurationStore.parseProjects(node.get("projects")),
                node.has("enabledMetrics") ? metrics(node) : store.snapshot().document().enabledMetrics(),
                requiredText(node, "candidateHash"), cn.datadashboard.security.OperationActor.authenticated(request.getUserPrincipal().getName()));
        if (node.has("enabledMetrics")) return store.apply(requiredText(node, "expectedRevision"),
                ProjectConfigurationStore.parseProjects(node.get("projects")), metrics(node), requiredText(node, "candidateHash"));
        return store.apply(requiredText(node, "expectedRevision"),
                ProjectConfigurationStore.parseProjects(node.get("projects")), requiredText(node, "candidateHash"));
    }

    private void verifyChangedIdentity(JsonNode node) {
        var before = store.snapshot();
        if (!before.revision().equals(requiredText(node, "expectedRevision"))) return; // store returns stale
        for (var p : ProjectConfigurationStore.parseProjects(node.get("projects"))) {
            var old = before.document().projects().stream().filter(b -> b.id().equals(p.id())).findFirst().orElse(null);
            if (old == null) {
                if (p.publicDouyinId() != null) {
                    var resolved = resolver.resolve(p.advertiserId(), p.publicDouyinId());
                    if (!p.storageAccountId().equals(resolved.awemeId()) || !p.douyinIds().equals(List.of(resolved.awemeId()))
                            || before.document().projects().stream().anyMatch(b -> b.douyinIds().contains(resolved.awemeId())
                            || java.util.Objects.equals(b.publicDouyinId(), p.publicDouyinId())))
                        throw new AnchorResolver.Failure("ANCHOR_IDENTITY_MISMATCH");
                    if (!unusedAccount.test(p.storageAccountId())) throw new AnchorResolver.Failure("ANCHOR_ACCOUNT_HAS_UNLINKED_DATA");
                }
                continue;
            }
            if (old.advertiserId().equals(p.advertiserId())
                    && old.douyinIds().equals(p.douyinIds()) && java.util.Objects.equals(old.publicDouyinId(), p.publicDouyinId())) continue;
            if (!store.lineage(p.id()).accounts().contains(p.storageAccountId()) && !unusedAccount.test(p.storageAccountId()))
                throw new AnchorResolver.Failure("ANCHOR_ACCOUNT_HAS_UNLINKED_DATA");
            var resolved = resolver.resolve(p.advertiserId(), p.publicDouyinId());
            var original = old.douyinIds().size() == 1 ? old.douyinIds().getFirst()
                    : resolver.resolve(old.advertiserId(), old.publicDouyinId()).awemeId();
            if (!original.equals(resolved.awemeId()) || !p.douyinIds().equals(List.of(original)))
                throw new AnchorResolver.Failure("ANCHOR_IDENTITY_MISMATCH");
        }
    }

    private void requireLocal(HttpServletRequest request, boolean write) {
        if (trialGuard != null) {
            if (!trialGuard.permits(request, write, true)) throw new GuardFailure("ACCESS_DENIED");
            if (write && !writable) throw new GuardFailure("CONFIG_WRITE_DISABLED");
            if (write && !"1".equals(request.getHeader("X-SCSJ-Config-Write"))) throw new GuardFailure("ACCESS_DENIED");
            return;
        }
        String host = request.getHeader("Host");
        String remote = request.getRemoteAddr();
        if (host == null || !HOSTS.contains(host) || remote == null
                || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remote))
            throw new GuardFailure("LOCAL_REQUEST_REQUIRED");
        String origin = request.getHeader("Origin");
        String site = request.getHeader("Sec-Fetch-Site");
        if ((origin != null && !ORIGINS.contains(origin)) || "cross-site".equals(site))
            throw new GuardFailure("LOCAL_REQUEST_REQUIRED");
        if (write && !writable) throw new GuardFailure("CONFIG_WRITE_DISABLED");
        if (write && (!ORIGINS.contains(origin == null ? "" : origin)
                || !"1".equals(request.getHeader("X-SCSJ-Config-Write"))))
            throw new GuardFailure("LOCAL_REQUEST_REQUIRED");
    }
    private JsonNode parse(String body, Set<String> fields) {
        if (body == null || body.length() > 65536) throw new IllegalArgumentException();
        JsonNode node = mapper.readTree(body);
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        Set<String> actual = node.properties().stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> withMetrics = new HashSet<>(fields);
        withMetrics.add("enabledMetrics");
        if (!actual.equals(fields) && !actual.equals(withMetrics))
            throw new IllegalArgumentException();
        return node;
    }
    private static List<String> metrics(JsonNode node) {
        JsonNode values = node.get("enabledMetrics");
        if (values == null || !values.isArray()) throw new IllegalArgumentException();
        List<String> selected = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isString()) throw new IllegalArgumentException();
            selected.add(value.stringValue());
        }
        return MetricSelection.validate(selected);
    }
    private static List<Map<String, Object>> metricCatalog() {
        Set<String> required = new HashSet<>();
        MetricSelection.profile(MetricSelection.allMetrics()).path("currentDashboardRequiredMetrics")
                .forEach(value -> required.add(value.stringValue()));
        return MetricSelection.allMetrics().stream().map(id -> Map.<String, Object>of(
                "id", id, "label", metricLabel(id), "required", required.contains(id))).toList();
    }
    private static String metricLabel(String id) {
        return switch (id) {
            case "stat_cost_for_roi2" -> "消耗";
            case "total_prepay_and_pay_order_roi2" -> "支付 ROI（来源值）";
            case "live_cvr_rate_for_roi2_v2" -> "点击率（来源值）";
            case "live_convert_rate_for_roi2_v2" -> "转化率（来源值）";
            case "live_show_count_for_roi2_v2" -> "曝光数";
            case "live_watch_count_for_roi2_v2" -> "点击数";
            case "total_pay_order_gmv_for_roi2" -> "成交金额";
            case "total_pay_order_count_for_roi2" -> "成交订单数";
            case "total_cost_per_pay_order_for_roi2" -> "成交订单成本";
            case "total_ecpm_for_roi2" -> "千次曝光成本（来源值）";
            case "video_like_count_for_roi2" -> "视频点赞数";
            case "video_follow_count_for_roi2" -> "视频关注数";
            case "video_avg_watch_duration_for_roi2" -> "视频平均观看时长";
            case "video_play_count_for_roi2_v2" -> "视频播放数";
            case "video_play_finish_rate_for_roi2_v2" -> "视频完播率";
            case "video_comment_count_for_roi2_v2" -> "视频评论数";
            case "video_play_duration_2s_rate_for_roi2" -> "视频 2 秒播放率";
            case "video_play_duration_3s_rate_for_roi2" -> "视频 3 秒播放率";
            case "video_play_duration_5s_rate_for_roi2" -> "视频 5 秒播放率";
            case "video_play_duration_10s_rate_for_roi2" -> "视频 10 秒播放率";
            case "additional_delivery_stat_cost_for_roi2_assist" -> "追投消耗";
            case "additional_delivery_total_prepay_and_pay_order_roi2_assist" -> "追投支付 ROI（来源值）";
            case "additional_delivery_total_pay_order_gmv_include_coupon_for_roi2_assist" -> "追投成交金额（含券）";
            case "additional_delivery_show_cnt_for_roi2_assist" -> "追投曝光数";
            case "additional_delivery_click_cnt_for_roi2_assist" -> "追投点击数";
            case "additional_delivery_ctr_for_roi2_assist" -> "追投点击率";
            case "additional_delivery_convert_rate_for_roi2_assist" -> "追投转化率";
            case "additional_delivery_total_pay_order_count_for_roi2_assist" -> "追投成交订单数";
            default -> id;
        };
    }
    private static String requiredText(JsonNode node, String name) {
        if (!node.path(name).isString()) throw new IllegalArgumentException();
        return node.path(name).stringValue();
    }
    @ExceptionHandler(GuardFailure.class)
    public ResponseEntity<Map<String, String>> guarded(GuardFailure failure) {
        return ResponseEntity.status(403).body(Map.of("code", failure.code));
    }
    @ExceptionHandler(AnchorResolver.Failure.class)
    public ResponseEntity<Map<String, String>> anchorFailure(AnchorResolver.Failure failure) {
        int status = switch (failure.code) {
            case "ANCHOR_INPUT_INVALID", "ANCHOR_NOT_FOUND", "ANCHOR_AMBIGUOUS", "ANCHOR_QUERY_REQUIRED" -> 400;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("code", failure.code));
    }
    @ExceptionHandler(ProjectConfigurationStore.ConfigurationException.class)
    public ResponseEntity<Map<String, String>> configurationFailure(ProjectConfigurationStore.ConfigurationException failure) {
        int status = switch (failure.code()) { case "CONFIG_INVALID" -> 400; case "CONFIG_STALE", "CONFIG_COLLECTION_WINDOW", "CONFIG_IDENTITY_MISMATCH" -> 409; default -> 503; };
        return ResponseEntity.status(status).body(Map.of("code", failure.code()));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "CONFIG_INVALID"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code", "CONFIG_UNAVAILABLE"));
    }
    public static final class GuardFailure extends RuntimeException {
        private final String code;
        GuardFailure(String code) { this.code = code; }
    }
}
