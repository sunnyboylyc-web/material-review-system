package cn.datadashboard.configuration;

import cn.datadashboard.api.ConfigurationController;
import cn.datadashboard.api.ProjectDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingProjectConfigurationTest {
    @TempDir Path root;
    final JsonMapper json = new JsonMapper();
    MockHttpServletRequest request() {
        var r = new MockHttpServletRequest(); r.setRemoteAddr("127.0.0.1");
        r.addHeader("Host","127.0.0.1:5180");r.addHeader("Origin","http://127.0.0.1:5180");r.addHeader("X-SCSJ-Config-Write","1");return r;
    }
    void save(ProjectConfigurationStore store, List<ProjectConfigurationStore.Project> projects) {
        var resolver = new AnchorResolver((a,p,n)->json.readTree("{\"code\":0,\"data\":{\"aweme_id_list\":[{\"aweme_id\":\"201\",\"aweme_show_id\":\"public.id\",\"aweme_name\":\"Synthetic\"}],\"page_info\":{\"page\":1,\"page_size\":100,\"total_page\":1,\"total_number\":1}}}"));
        var controller = new ConfigurationController(store, true, resolver).withUnusedAccountCheck(a -> true);
        String revision = store.snapshot().revision();
        var preflight = (ProjectConfigurationStore.Preflight) controller.preflight(json.writeValueAsString(Map.of("expectedRevision",revision,"projects",projects)),request());
        controller.apply(json.writeValueAsString(Map.of("expectedRevision",revision,"projects",projects,"candidateHash",preflight.candidateHash())),request());
    }
    @Test void pendingSaveSidebarIsolationDeleteRestoreRestart() throws Exception {
        Files.createDirectory(root.resolve(".local")); Path path = root.resolve(".local/config");
        var store = new ProjectConfigurationStore(root,path);
        var existing = new ProjectConfigurationStore.Project("existing","旧项目","100",List.of("200"),true);
        store.initialize(List.of(existing));
        var pending = new ProjectConfigurationStore.Project("new-project","新项目","101",List.of("201"),true,"public.id","Synthetic",false,"OTHER","QUANYU","201");
        save(store,List.of(existing,pending));
        var directory = new ProjectDirectory(()->store.snapshot().document().projects().stream()
                .map(p->new ProjectDirectory.Binding(p.id(),p.name(),p.advertiserId(),p.enabled()&&!p.deleted())).toList());
        assertEquals(2,directory.projects().size()); assertEquals("101",directory.account("new-project"));
        assertEquals("100",directory.account("existing"));
        var deleted = new ProjectConfigurationStore.Project("new-project","新项目","101",List.of("201"),false,"public.id","Synthetic",true,"OTHER","QUANYU","201");
        save(store,List.of(existing,deleted));
        assertEquals(1,directory.projects().size());
        assertThrows(ProjectDirectory.ProjectNotEnabled.class,()->directory.account("new-project"));
        assertEquals(deleted,new ProjectConfigurationStore(root,path).snapshot().document().projects().get(1));
        save(store,List.of(existing,pending));assertEquals("101",directory.account("new-project"));
        assertThrows(RuntimeException.class,()->save(store,List.of(existing)));
        var invalid = new ProjectConfigurationStore.Project("new-project","新项目","101",List.of(),true,"public.id",null,true);
        assertThrows(RuntimeException.class,()->save(store,List.of(existing,invalid)));
    }
    @Test void rejectsUnidentifiedEmptyBindingAndMalformedDeletedFlag() {
        assertThrows(RuntimeException.class,()->ProjectConfigurationStore.parseProjects(json.readTree("[{\"id\":\"new\",\"name\":\"x\",\"advertiserId\":\"101\",\"douyinIds\":[],\"enabled\":true}]")));
        assertThrows(RuntimeException.class,()->ProjectConfigurationStore.parseProjects(json.readTree("[{\"id\":\"new\",\"name\":\"x\",\"advertiserId\":\"101\",\"douyinIds\":[],\"enabled\":false,\"publicDouyinId\":\"public.id\",\"deleted\":\"true\"}]")));
    }
}
