package cn.datadashboard.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class McpServiceSecurityConfigurationTest {
    @TempDir Path workspace;
    static final String INTERNAL = "test-service-" + "s".repeat(40);
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,FlywayAutoConfiguration.class})
    @Import({TrialSecurityConfiguration.class,ReadRoutes.class})
    static class App { }
    @RestController static class ReadRoutes {
        @GetMapping({"/api/projects","/api/projects/{id}/review","/api/projects/{id}/top",
                "/api/projects/{id}/material-types","/api/projects/{id}/review-materials","/api/projects/{id}/freshness"})
        String read(){return "synthetic-read";}
    }
    private SpringApplication app(boolean publicAccess, boolean configured, int serial) throws Exception {
        Path key=workspace.resolve("service-"+serial);Files.writeString(key,INTERNAL);
        var a=new SpringApplication(App.class);a.setLogStartupInfo(false);
        a.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),
                Map.entry("server.servlet.context-path","/app"),Map.entry("logging.level.root","OFF"),
                Map.entry("spring.main.banner-mode","off"),Map.entry("scsj.auth.public-access",String.valueOf(publicAccess)),
                Map.entry("scsj.auth.bootstrap-admin-only","true"),Map.entry("scsj.auth.admin-password-hash",new BCryptPasswordEncoder().encode("synthetic-password")),
                Map.entry("scsj.auth.state-directory",workspace.resolve("accounts-"+serial).toString()),
                Map.entry("scsj.mcp.upstream-token-file",configured?key.toString():"")));
        return a;
    }
    private HttpResponse<String> request(String base,String method,String path,String... keys) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).method(method,HttpRequest.BodyPublishers.noBody());
        for(String key:keys)b.header(McpServiceSecurityConfiguration.HEADER,key);
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void serviceReadsSurviveWebsiteLoginSwitchAndCannotWriteOrBecomeWebsiteUser() throws Exception {
        for(boolean publicAccess:new boolean[]{true,false})try(var context=app(publicAccess,true,publicAccess?1:2).run()) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort()+"/app";
            assertEquals(publicAccess?200:401,request(base,"GET","/api/projects").statusCode());
            for(String path:new String[]{"/api/projects","/api/projects/demo/review","/api/projects/demo/review-materials",
                    "/api/projects/demo/material-types","/api/projects/demo/top","/api/projects/demo/freshness"}) {
                var response=request(base,"GET",path,INTERNAL);assertEquals(200,response.statusCode(),path);
                assertEquals("synthetic-read",response.body());assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
                assertEquals(200,request(base,"GET",path+"?currentStart=2026-01-01&currentEnd=2026-01-07",INTERNAL).statusCode(),path+" with query parameters");
            }
            assertEquals(401,request(base,"GET","/api/projects","external-only-"+"x".repeat(40)).statusCode());
            assertEquals(401,request(base,"GET","/api/projects",INTERNAL,INTERNAL).statusCode());
            for(String path:new String[]{"/api/projects","/api/operations/history","/api/admin/accounts","/api/projects/demo/write"})
                assertEquals(403,request(base,"POST",path,INTERNAL).statusCode(),path);
            for(String path:new String[]{"/","/assets/a.js","/api/auth/session","/api/operations/configuration","/api/admin/accounts","/api/projects/demo/unknown"})
                assertEquals(403,request(base,"GET",path,INTERNAL).statusCode(),path);
            assertEquals(publicAccess?200:401,request(base,"GET","/api/projects").statusCode());
        }
    }
    @Test void unconfiguredCredentialFailsClosedEvenWhenWebsiteIsPublic() throws Exception {
        try(var context=app(true,false,3).run()) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort()+"/app";
            assertEquals(200,request(base,"GET","/api/projects").statusCode());
            var response=request(base,"GET","/api/projects",INTERNAL);
            assertEquals(401,response.statusCode());assertFalse(response.body().contains(INTERNAL));
        }
    }
}
