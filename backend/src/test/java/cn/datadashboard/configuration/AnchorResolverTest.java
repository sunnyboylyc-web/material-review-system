package cn.datadashboard.configuration;

import cn.datadashboard.api.ConfigurationController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnchorResolverTest {
    final JsonMapper json = new JsonMapper();
    @TempDir Path workspace;
    JsonNode page(List<Map<String, Object>> rows, int page, int total) {
        return json.valueToTree(Map.of("code", 0, "data", Map.of("aweme_id_list", rows,
                "page_info", Map.of("page", page, "page_size", 100, "total_page", (total + 99) / 100, "total_number", total))));
    }
    Map<String, Object> row(String id, String show) { return Map.of("aweme_id", id, "aweme_show_id", show, "aweme_name", "合成名称"); }
    @Test void handlesKnownProtocolEnvelopesAndRejectsMismatch() {
        var business = page(List.of(row("1", "target")),1,1);
        assertEquals(business, AnchorResolver.businessResult(business));
        assertEquals(business, AnchorResolver.businessResult(json.valueToTree(Map.of("structuredContent", business))));
        assertEquals(business, AnchorResolver.businessResult(json.valueToTree(Map.of("content", List.of(Map.of("type","text","text",json.writeValueAsString(business)))))));
        for (int status : List.of(200,202,204)) assertTrue(AnchorResolver.rpcResult(status,new byte[0],"synthetic-secret","id",true).isObject());
        byte[] envelope = json.writeValueAsBytes(Map.of("jsonrpc","2.0","id","id","result",Map.of("structuredContent",business)));
        assertTrue(AnchorResolver.rpcResult(200,envelope,"synthetic-secret","id",false).has("structuredContent"));
        assertThrows(AnchorResolver.Failure.class,()->AnchorResolver.rpcResult(200,envelope,"synthetic-secret","wrong",false));
        assertThrows(AnchorResolver.Failure.class,()->AnchorResolver.rpcResult(202,new byte[0],"synthetic-secret","id",false));
        assertThrows(AnchorResolver.Failure.class,()->AnchorResolver.businessResult(json.valueToTree(Map.of("isError",true))));
    }
    @Test void exactMatchNotFirstAndCompletePagination() {
        var resolver = new AnchorResolver((a,p,n) -> {
            if (n == 2) return page(List.of(row("999", "target")), 2, 101);
            List<Map<String,Object>> rows = new ArrayList<>();
            for (int i=1;i<=100;i++) rows.add(row(""+i, "other"+i));
            return page(rows,1,101);
        });
        assertEquals("999", resolver.resolve("100", "target").awemeId());
    }
    @Test void rejectNoMatchAmbiguityIncompleteAndProviderFailure() {
        for (JsonNode response : List.of(page(List.of(row("1", "other")),1,1),
                page(List.of(row("1", "target"),row("2", "target")),1,2),
                page(List.of(row("1", "target")),1,2), json.valueToTree(Map.of("code", 401)))) {
            assertThrows(AnchorResolver.Failure.class, () -> new AnchorResolver((a,p,n)->response).resolve("100","target"));
        }
    }
    @Test void rejectsPublicAsInternalAndBindingMutation() {
        var resolver = new AnchorResolver((a,p,n)->page(List.of(row("999","target")),1,1));
        resolver.resolve("100","target");
        var wrong = new ProjectConfigurationStore.Project("test","name","100",List.of("111"),true,"target","合成名称");
        assertThrows(AnchorResolver.Failure.class,()->resolver.requireVerified(List.of(),List.of(wrong)));
        var otherAccount = new ProjectConfigurationStore.Project("test","name","101",List.of("999"),true,"target","合成名称");
        assertThrows(AnchorResolver.Failure.class,()->resolver.requireVerified(List.of(),List.of(otherAccount)));
    }
    MockHttpServletRequest request() {
        var r = new MockHttpServletRequest(); r.setRemoteAddr("127.0.0.1");
        r.addHeader("Host","127.0.0.1:5180");r.addHeader("Origin","http://127.0.0.1:5180");r.addHeader("X-SCSJ-Config-Write","1");return r;
    }
    @Test void guardedResolvePreflightSaveAndRestartPersistence() throws Exception {
        Files.createDirectory(workspace.resolve(".local"));
        var store = new ProjectConfigurationStore(workspace,workspace.resolve(".local/config")); store.initialize(List.of());
        var resolver = new AnchorResolver((a,p,n)->page(List.of(row("999","target")),1,1));
        var controller = new ConfigurationController(store,true,resolver).withUnusedAccountCheck(a -> true);
        String lookup = "{\"advertiserId\":\"100\",\"publicDouyinId\":\"target\"}";
        var bad = request();bad.removeHeader("Origin");
        assertThrows(ConfigurationController.GuardFailure.class,()->controller.resolveAnchor(lookup,bad));
        var project = new ProjectConfigurationStore.Project("test","name","100",List.of("999"),true,"target","合成名称",false,"OTHER","QUANYU","999");
        String revision = store.snapshot().revision();
        String draft = json.writeValueAsString(Map.of("expectedRevision",revision,"projects",List.of(project)));
        assertNotNull(controller.preflight(draft,request())); // D080 no manual lookup gate before saving.
        controller.resolveAnchor(lookup,request());
        var checked = (ProjectConfigurationStore.Preflight)controller.preflight(draft,request());
        assertEquals(revision,store.snapshot().revision());
        controller.apply(json.writeValueAsString(Map.of("expectedRevision",revision,"projects",List.of(project),"candidateHash",checked.candidateHash())),request());
        var restarted = new ProjectConfigurationStore(workspace,workspace.resolve(".local/config"));
        assertEquals(project,restarted.snapshot().document().projects().getFirst());
        var newController = new ConfigurationController(restarted,true);
        assertNotNull(newController.preflight(json.writeValueAsString(Map.of("expectedRevision",restarted.snapshot().revision(),"projects",List.of(project))),request()));
    }
}
