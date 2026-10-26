package cn.datadashboard.configuration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit user-triggered authorized-account lookup only; never reads/writes report facts. */
public final class AnchorResolver {
    public static final String TOOL = "qianchuan_uni_aweme_authorized_get_v1";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public interface Lookup { JsonNode page(String advertiser, String publicId, int page); }
    public record Result(String advertiserId, String publicDouyinId, String awemeId, String awemeName) { }
    private final Lookup lookup;
    private final Map<String, Result> verified = new HashMap<>();
    public AnchorResolver(Lookup lookup) { this.lookup = lookup; }
    public static AnchorResolver local(Path workspace) {
        return new AnchorResolver((account, publicId, page) -> remotePage(workspace, account, publicId, page));
    }
    public synchronized Result resolve(String advertiser, String publicId) {
        check(advertiser != null && advertiser.matches("[1-9][0-9]{0,29}") && publicId != null
                && publicId.matches("[A-Za-z0-9_.-]{1,64}"), "ANCHOR_INPUT_INVALID");
        verified.remove(advertiser + ":" + publicId);
        List<Result> matches = new ArrayList<>(); Set<String> seen = new HashSet<>();
        int pages = -1, total = -1, count = 0;
        for (int page = 1; page <= 100; page++) {
            JsonNode response = lookup.page(advertiser, publicId, page);
            check(response != null && response.path("code").isIntegralNumber() && response.path("code").intValue() == 0, "ANCHOR_PROVIDER_REJECTED");
            JsonNode data = response.path("data"), info = data.path("page_info"), rows = data.path("aweme_id_list");
            int actualPage = integer(info, "page"), pageSize = integer(info, "page_size");
            int pageTotal = integer(info, "total_page"), rowTotal = integer(info, "total_number");
            check(actualPage == page && pageSize == 100 && pageTotal >= 0 && pageTotal <= 100 && rowTotal >= 0
                    && (pageTotal == (rowTotal + 99) / 100 || (rowTotal == 0 && pageTotal == 1)) && rows.isArray(), "ANCHOR_PAGE_INVALID");
            check(!info.has("has_more") || (info.get("has_more").isBoolean() && info.get("has_more").booleanValue() == (page < pageTotal)), "ANCHOR_PAGE_INVALID");
            if (pages < 0) { pages = pageTotal; total = rowTotal; }
            check(pages == pageTotal && total == rowTotal && rows.size() == Math.min(100, Math.max(0, total - (page - 1) * 100)), "ANCHOR_PAGE_INVALID");
            for (JsonNode row : rows) {
                String id = row.path("aweme_id").asText(), show = row.path("aweme_show_id").asText();
                check(id.matches("[1-9][0-9]{0,29}") && seen.add(id) && row.path("aweme_show_id").isString()
                        && row.path("aweme_name").isString() && !row.path("aweme_name").stringValue().isBlank()
                        && row.path("aweme_name").stringValue().length() <= 200, "ANCHOR_PAGE_INVALID");
                count++;
                if (publicId.equals(show)) matches.add(new Result(advertiser, show, id, row.path("aweme_name").stringValue()));
            }
            if (page >= pages) break;
        }
        check(count == total, "ANCHOR_PAGE_INVALID");
        check(!matches.isEmpty(), "ANCHOR_NOT_FOUND");
        check(matches.size() == 1, "ANCHOR_AMBIGUOUS");
        Result result = matches.getFirst(); verified.put(advertiser + ":" + publicId, result); return result;
    }
    public synchronized void requireVerified(List<ProjectConfigurationStore.Project> before, List<ProjectConfigurationStore.Project> after) {
        for (var project : after) {
            var old = before.stream().filter(p -> p.id().equals(project.id())).findFirst().orElse(null);
            if (old != null && old.advertiserId().equals(project.advertiserId()) && old.douyinIds().equals(project.douyinIds())
                    && Objects.equals(old.publicDouyinId(), project.publicDouyinId()) && Objects.equals(old.awemeName(), project.awemeName())) continue;
            Result result = verified.get(project.advertiserId() + ":" + project.publicDouyinId());
            check(result != null && project.douyinIds().equals(List.of(result.awemeId()))
                    && Objects.equals(project.awemeName(), result.awemeName()), "ANCHOR_QUERY_REQUIRED");
        }
    }
    private static int integer(JsonNode node, String key) {
        check(node.path(key).isIntegralNumber() && node.path(key).canConvertToInt(), "ANCHOR_PAGE_INVALID");
        return node.path(key).intValue();
    }
    private static JsonNode remotePage(Path workspace, String advertiser, String publicId, int page) {
        try {
            String configured = System.getenv("SCSJ_MCP_CREDENTIALS");
            check(configured != null, "ANCHOR_CONNECTION_UNAVAILABLE");
            Path path = Path.of(configured).toAbsolutePath().normalize(), root = workspace.toRealPath().resolve(".local");
            check(path.startsWith(root) && path.toRealPath().equals(path) && Files.isRegularFile(path) && Files.size(path) <= 16384, "ANCHOR_CONNECTION_UNAVAILABLE");
            JsonNode secret = JSON.readTree(Files.readAllBytes(path));
            String key = secret.path("apiKey").asText();
            check(secret.size() == 1 && secret.path("apiKey").isString() && key.length() >= 8 && key.length() <= 8192 && key.equals(key.strip()), "ANCHOR_CONNECTION_UNAVAILABLE");
            JsonNode init = rpc(key, "initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "scsj-anchor-lookup", "version", "1")));
            check(init.isObject(), "ANCHOR_PROTOCOL_INVALID");
            rpc(key, "notifications/initialized", Map.of());
            JsonNode result = rpc(key, "tools/call", Map.of("name", TOOL, "arguments", Map.of("advertiser_id", advertiser,
                    "filtering", Map.of("marketing_goal", "LIVE_PROM_GOODS", "scene", "CREATE", "search_key_words", publicId), "page", page, "page_size", 100)));
            return businessResult(result);
        } catch (Failure failure) { throw failure; }
        catch (Exception failure) { throw new Failure("ANCHOR_CONNECTION_UNAVAILABLE"); }
    }
    static JsonNode businessResult(JsonNode result) {
            check(!result.path("isError").asBoolean(false), "ANCHOR_PROVIDER_REJECTED");
            if (result.has("structuredContent")) {
                check(result.get("structuredContent").isObject(), "ANCHOR_PROTOCOL_INVALID");
                return result.get("structuredContent");
            }
            if (result.has("code") && result.path("data").isObject()) return result;
            JsonNode content = result.path("content");
            check(content.isArray() && content.size() == 1 && "text".equals(content.get(0).path("type").asText()) && content.get(0).path("text").isString(), "ANCHOR_PROTOCOL_INVALID");
            return JSON.readTree(content.get(0).path("text").stringValue());
    }
    private static JsonNode rpc(String key, String method, Map<String, Object> params) throws Exception {
        String id = UUID.randomUUID().toString(); boolean notification = method.startsWith("notifications/");
        var payload = new HashMap<String, Object>(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        if (!notification) payload.put("id", id);
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15))
                .proxy(new java.net.ProxySelector() {
                    public List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) { }
                }).build();
        var request = HttpRequest.newBuilder(URI.create("https://open.oceanengine.com/qianchuan/mcp?API_KEY=" + URLEncoder.encode(key, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("Tool-Range", "[\"" + TOOL + "\"]").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var body = response.body()) {
            var read = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return body.readNBytes(2 * 1024 * 1024 + 1); }
                catch (java.io.IOException failure) { throw new Failure("ANCHOR_CONNECTION_UNAVAILABLE"); }
            });
            try { bytes = read.get(45, java.util.concurrent.TimeUnit.SECONDS); }
            finally { read.cancel(true); }
        }
        return rpcResult(response.statusCode(), bytes, key, id, notification);
    }
    static JsonNode rpcResult(int status, byte[] bytes, String key, String id, boolean notification) {
        check(bytes.length <= 2 * 1024 * 1024, "ANCHOR_CONNECTION_UNAVAILABLE");
        if (notification && Set.of(200, 202, 204).contains(status) && bytes.length == 0) return JSON.createObjectNode();
        check(status == 200, "ANCHOR_CONNECTION_UNAVAILABLE");
        String raw = new String(bytes, StandardCharsets.UTF_8);
        check(!raw.contains(key) && !raw.contains(URLEncoder.encode(key, StandardCharsets.UTF_8)), "ANCHOR_PROTOCOL_INVALID");
        JsonNode result = JSON.readTree(bytes);
        check("2.0".equals(result.path("jsonrpc").asText()) && !result.has("error") && result.path("result").isObject()
                && (notification || id.equals(result.path("id").asText())), "ANCHOR_PROTOCOL_INVALID");
        check(!JSON.writeValueAsString(result).contains(key), "ANCHOR_PROTOCOL_INVALID");
        return result.path("result");
    }
    private static void check(boolean ok, String code) { if (!ok) throw new Failure(code); }
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }
}
