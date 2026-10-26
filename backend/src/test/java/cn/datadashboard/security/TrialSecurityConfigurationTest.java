package cn.datadashboard.security;

import org.junit.jupiter.api.Test;
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

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TrialSecurityConfigurationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({TrialSecurityConfiguration.class, TrialSessionController.class, StubRoutes.class})
    static class TrialTestApp { }

    @RestController
    static class StubRoutes {
        @GetMapping("/") String home() { return "home"; }
        @GetMapping("/api/projects") String projects() { return "projects"; }
        @GetMapping("/api/operations/exports/projects/p/download") String download() { return "download"; }
        @GetMapping("/api/operations/history/template") String template() { return "template"; }
        @org.springframework.web.bind.annotation.PostMapping("/api/operations/public-test")
        String write(jakarta.servlet.http.HttpServletRequest request) {
            if (!new TrialRequestGuard("https://trial.example").permits(request,true,true))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            return "saved";
        }
    }

    @Test
    void publicModeNeedsNoAccountsAllowsOperationsAndKeepsCsrfOriginAndSessionIsolation() throws Exception {
        var app = new SpringApplication(TrialTestApp.class);
        var directory = cn.datadashboard.api.ProjectDirectory.single("10000001");
        java.nio.file.Files.createDirectories(workspace.resolve(".local"));
        var configuration = new cn.datadashboard.configuration.ProjectConfigurationStore(workspace,workspace.resolve(".local/configuration"));
        configuration.initialize(java.util.List.of(new cn.datadashboard.configuration.ProjectConfigurationStore.Project(
                "synthetic","test","10000001",java.util.List.of("20000001"),true)));
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("publicConfiguration",
                new cn.datadashboard.api.ConfigurationController(configuration,true,
                        new cn.datadashboard.configuration.AnchorResolver((a,p,n) -> { throw new IllegalStateException("NO_NETWORK"); }),
                        new TrialRequestGuard("https://trial.example"))));
        var files = new cn.datadashboard.files.OperationsFileStore(workspace.resolve(".local/files"));
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("publicFiles",
                new cn.datadashboard.api.OperationsFilesController(files,new TrialRequestGuard("https://trial.example"),true)));
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("preferences",
                new PeriodPreferenceController(workspace.resolve("preferences"),directory)));
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0",
                "server.servlet.context-path","/uranus/titania","logging.level.root","OFF",
                "spring.main.banner-mode","off","scsj.auth.public-access","true",
                "scsj.auth.state-directory",workspace.resolve("unused-accounts").toString()));
        try (var context = app.run()) {
            assertTrue(context.getBeansOfType(PrivateAccountStore.class).isEmpty());
            assertTrue(context.getBeansOfType(DurableRememberMeServices.class).isEmpty());
            assertTrue(context.getBeansOfType(AccountManagementController.class).isEmpty());
            assertFalse(java.nio.file.Files.exists(workspace.resolve("unused-accounts")));
            String root = "http://127.0.0.1:" + ((WebServerApplicationContext)context).getWebServer().getPort() + "/uranus/titania";
            var first = new Client(root); var second = new Client(root);
            assertEquals(200,first.get("/").statusCode());
            var login = first.get("/login");
            assertEquals(302,login.statusCode());
            assertEquals("/uranus/titania/",login.headers().firstValue("Location").orElseThrow());
            assertFalse(login.body().contains("password"));
            assertEquals(200,first.get("/api/projects").statusCode());
            assertEquals(200,first.get("/api/operations/history/template").statusCode());
            assertEquals(200,first.get("/api/operations/exports/projects/p/download").statusCode());
            assertEquals(403,first.get("/api/admin/accounts").statusCode());
            assertEquals(403,first.get("/api/not-registered").statusCode());
            var session = first.get("/api/auth/session");
            assertEquals(200,session.statusCode());
            assertTrue(session.body().contains("\"publicAccess\":true"));
            assertTrue(session.body().contains("\"operations\":true"));
            assertTrue(session.body().contains("\"administrator\":false"));
            String token = capture(SESSION_CSRF,session.body());
            var mapper = new tools.jackson.databind.json.JsonMapper();
            var configured = configuration.snapshot();
            String candidate = mapper.writeValueAsString(Map.of("expectedRevision",configured.revision(),"projects",configured.document().projects()));
            var preflight = first.postJson("/api/operations/configuration/preflight",token,candidate);
            assertEquals(200,preflight.statusCode());
            String apply = mapper.writeValueAsString(Map.of("expectedRevision",configured.revision(),"projects",configured.document().projects(),
                    "candidateHash",mapper.readTree(preflight.body()).path("candidateHash").stringValue()));
            assertEquals(200,first.postJson("/api/operations/configuration/apply",token,apply).statusCode());
            assertTrue(configuration.snapshot().document().actor().matches("AUTHENTICATED:public-[a-f0-9]{32}"));
            assertEquals(403,first.post("/api/operations/public-test",null).statusCode());
            assertEquals(403,first.post("/api/operations/public-test",token).statusCode()); // Missing Origin.
            assertEquals(200,first.postJson("/api/operations/public-test",token,"{}").statusCode());
            assertEquals(403,second.postJson("/api/operations/public-test",token,"{}").statusCode());
            String path = "/api/preferences/projects/sampleproject/period";
            String value = "{\"currentStart\":\"2026-10-01\",\"currentEnd\":\"2026-10-07\",\"previousStart\":\"2026-09-01\",\"previousEnd\":\"2026-09-07\"}";
            assertEquals(200,first.putJson(path,token,value).statusCode());
            assertEquals(200,first.get(path).statusCode());
            assertEquals(204,second.get(path).statusCode());
            assertEquals(405,first.postJson("/login",token,"{}").statusCode());
            var created = first.postJson("/api/operations/files",token,"{\"name\":\"public-test.txt\",\"bytes\":4}");
            assertEquals(200,created.statusCode());
            String fileId = new tools.jackson.databind.json.JsonMapper().readTree(created.body()).path("id").stringValue();
            assertEquals(200,first.putBytes("/api/operations/files/"+fileId+"/chunks?offset=0",token,"test").statusCode());
            assertEquals(200,first.postJson("/api/operations/files/"+fileId+"/complete",token,"{}").statusCode());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!files.list().getFirst().status().equals("READY") && System.nanoTime()<deadline) Thread.sleep(10);
            var download = second.get("/api/operations/files/"+fileId+"/download");
            assertEquals(200,download.statusCode()); assertEquals("test",download.body());
            String secondToken = capture(SESSION_CSRF,second.get("/api/auth/session").body());
            assertEquals(400,second.deleteJson("/api/operations/files/"+fileId,secondToken,"{\"confirmed\":false}").statusCode());
            assertEquals(200,second.deleteJson("/api/operations/files/"+fileId,secondToken,"{\"confirmed\":true}").statusCode());
            assertTrue(files.list().isEmpty());
        }
    }

    private static final Pattern LOGIN_CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern SESSION_CSRF = Pattern.compile("\"csrfToken\":\"([^\"]+)\"");
    private static final String VIEWER_PASSWORD = "Viewer-test-123!";
    private static final String OPS_PASSWORD = "Operations-test-456!";
    private static final String ADMIN_PASSWORD = "Administrator-test-789!";

    @Test
    void roleMatrixAndCsrfAreEnforcedByServer() throws Exception {
        var encoder = new BCryptPasswordEncoder();
        var app = new SpringApplication(TrialTestApp.class);
        java.nio.file.Files.createDirectories(workspace.resolve(".local"));
        var store = new cn.datadashboard.configuration.ProjectConfigurationStore(workspace, workspace.resolve(".local/configuration"));
        store.initialize(java.util.List.of(new cn.datadashboard.configuration.ProjectConfigurationStore.Project(
                "synthetic", "test", "10000001", java.util.List.of("20000001"), true)));
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("realConfigurationController",
                new cn.datadashboard.api.ConfigurationController(store, true,
                    new cn.datadashboard.configuration.AnchorResolver((a,p,n) -> { throw new IllegalStateException("NO_NETWORK"); }),
                    new TrialRequestGuard("https://trial.example"))));
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("preferences",
                new PeriodPreferenceController(workspace.resolve("preferences"), cn.datadashboard.api.ProjectDirectory.single("10000001"))));
        var reviewDirectory=cn.datadashboard.api.ProjectDirectory.single("10000001");
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("collectionStatus",
                new cn.datadashboard.api.CollectionStatusController(new cn.datadashboard.operations.CollectionStatusReader(workspace, reviewDirectory))));
        var reviewReads=new java.util.concurrent.atomic.AtomicInteger();
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("reviewSnapshots",
                new ReviewSnapshotController(workspace.resolve("review-snapshots"),reviewDirectory,
                    new cn.datadashboard.api.DashboardController(reviewDirectory,a->{ reviewReads.incrementAndGet(); return new cn.datadashboard.calculation.DashboardModels.Snapshot(a,java.util.List.of(),java.util.List.of()); }))));
        var files = new cn.datadashboard.files.OperationsFileStore(workspace.resolve(".local/files"));
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("privateFiles",
                new cn.datadashboard.api.OperationsFilesController(files, new TrialRequestGuard("https://trial.example"), true)));
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of(
                "server.address", "127.0.0.1", "server.port", "0",
                "scsj.auth.state-directory", workspace.resolve("auth").toString(),
                "logging.level.root", "OFF", "spring.main.banner-mode", "off",
                "scsj.auth.viewer-password-hash", encoder.encode(VIEWER_PASSWORD),
                "scsj.auth.admin-password-hash", encoder.encode(ADMIN_PASSWORD),
                "scsj.auth.operations-password-hash", encoder.encode(OPS_PASSWORD)));
        try (var context = app.run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String root = "http://127.0.0.1:" + port;
            Client guest = new Client(root);
            assertEquals(401, guest.get("/api/projects").statusCode());
            assertEquals(401, guest.get("/api/operations/exports/projects/p/download").statusCode());
            assertEquals(401, guest.get("/api/operations/configuration").statusCode());
            assertEquals(401, guest.get("/api/not-registered").statusCode());
            assertEquals(401, guest.get("/api/preferences/projects/sampleproject/period").statusCode());
            assertEquals(401, guest.get("/api/preferences/projects/sampleproject/review-snapshot").statusCode());
            assertEquals(401, guest.get("/api/operations/files").statusCode());
            assertEquals(401, guest.get("/api/operations/collection-status").statusCode());
            assertEquals(403, guest.post("/api/operations/files", null).statusCode());

            Client viewer = new Client(root);
            viewer.login("123456", VIEWER_PASSWORD);
            assertEquals(403, viewer.get("/api/not-registered").statusCode());
            assertEquals(403, viewer.get("/api/admin/accounts").statusCode());
            assertEquals(200, viewer.get("/api/projects").statusCode());
            assertEquals(200, viewer.get("/api/operations/exports/projects/p/download").statusCode());
            assertEquals(403, viewer.get("/api/operations/configuration").statusCode());
            assertEquals(403, viewer.get("/api/operations/history/template").statusCode());
            assertEquals(403, viewer.post("/api/operations/configuration/apply", null).statusCode());
            assertEquals(403, viewer.get("/api/operations/files").statusCode());
            assertEquals(403, viewer.get("/api/operations/collection-status").statusCode());
            assertEquals(403, viewer.postJson("/api/operations/files", capture(SESSION_CSRF, viewer.get("/api/auth/session").body()), "{\"name\":\"backup.zip\",\"bytes\":4}").statusCode());
            String preferencesPath = "/api/preferences/projects/sampleproject/period";
            String periodJson = "{\"currentStart\":\"2026-10-01\",\"currentEnd\":\"2026-10-07\",\"previousStart\":\"2026-09-01\",\"previousEnd\":\"2026-09-07\"}";
            assertEquals(204, viewer.get(preferencesPath).statusCode());
            assertEquals(403, viewer.putJson(preferencesPath, null, periodJson).statusCode());
            assertEquals(200, viewer.putJson(preferencesPath, capture(SESSION_CSRF, viewer.get("/api/auth/session").body()), periodJson).statusCode());
            assertEquals(200, viewer.get(preferencesPath).statusCode());
            String reviewPath="/api/preferences/projects/sampleproject/review-snapshot";
            assertEquals(204,viewer.get(reviewPath).statusCode());
            assertEquals(403,viewer.post(reviewPath+"/refresh",null).statusCode());
            String reviewQuery=periodJson.substring(0,periodJson.length()-1)+",\"limit\":20}";
            assertEquals(200,viewer.postJson(reviewPath+"/refresh",capture(SESSION_CSRF,viewer.get("/api/auth/session").body()),reviewQuery).statusCode());
            assertEquals(1,reviewReads.get());
            assertEquals(200,viewer.get(reviewPath).statusCode()); assertEquals(1,reviewReads.get());

            Client operations = new Client(root);
            operations.login("asdfgh", OPS_PASSWORD);
            var collectionStatus = operations.get("/api/operations/collection-status");
            assertEquals(200, collectionStatus.statusCode());
            assertTrue(collectionStatus.body().contains("NOT_OBSERVED"));
            assertEquals(403,operations.get("/api/admin/accounts").statusCode());
            assertEquals(204,operations.get(reviewPath).statusCode());
            assertEquals(204, operations.get(preferencesPath).statusCode());
            assertEquals(200, operations.get("/api/projects").statusCode());
            assertEquals(200, operations.get("/api/operations/exports/projects/p/download").statusCode());
            assertEquals(200, operations.get("/api/operations/configuration").statusCode());
            assertEquals(200, operations.get("/api/operations/history/template").statusCode());
            assertEquals(403, operations.post("/api/operations/configuration/apply", null).statusCode());
            var session = operations.get("/api/auth/session");
            assertEquals(200, session.statusCode());
            assertTrue(session.body().contains("\"operations\":true"));
            var mapper = new tools.jackson.databind.json.JsonMapper();
            var snapshot = store.snapshot();
            String csrf = capture(SESSION_CSRF, session.body());
            assertEquals(200, operations.get("/api/operations/files").statusCode());
            assertEquals(403, operations.post("/api/operations/files", null).statusCode());
            var created = operations.postJson("/api/operations/files", csrf, "{\"name\":\"backup.zip\",\"bytes\":4}");
            assertEquals(200, created.statusCode());
            String fileId = mapper.readTree(created.body()).path("id").stringValue();
            assertEquals(403, operations.putBytes("/api/operations/files/" + fileId + "/chunks?offset=0", null, "test").statusCode());
            assertEquals(200, operations.putBytes("/api/operations/files/" + fileId + "/chunks?offset=0", csrf, "test").statusCode());
            assertEquals(200, operations.postJson("/api/operations/files/" + fileId + "/complete", csrf, "{}").statusCode());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!files.list().getFirst().status().equals("READY") && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("READY", files.list().getFirst().status());
            String downloadPath = "/api/operations/files/" + fileId + "/download";
            assertEquals(401, guest.get(downloadPath).statusCode());
            assertEquals(403, viewer.get(downloadPath).statusCode());
            var downloaded = operations.get(downloadPath);
            assertEquals(200, downloaded.statusCode()); assertEquals("test", downloaded.body());
            assertTrue(downloaded.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"));
            assertEquals("no-store", downloaded.headers().firstValue("Cache-Control").orElse(""));
            assertEquals("no", downloaded.headers().firstValue("X-Accel-Buffering").orElse(""));
            assertEquals(403, operations.get("/.local/files/" + fileId + ".bin").statusCode());
            String deletePath = "/api/operations/files/" + fileId;
            assertEquals(403, guest.deleteJson(deletePath,null,"{\"confirmed\":true}").statusCode());
            assertEquals(403, viewer.deleteJson(deletePath,capture(SESSION_CSRF,viewer.get("/api/auth/session").body()),"{\"confirmed\":true}").statusCode());
            assertEquals(403, operations.deleteJson(deletePath,null,"{\"confirmed\":true}").statusCode());
            assertEquals(400, operations.deleteJson(deletePath,csrf,"{\"confirmed\":false}").statusCode());
            assertEquals(200,operations.get(downloadPath).statusCode());
            assertEquals(200,operations.deleteJson(deletePath,csrf,"{\"confirmed\":true}").statusCode());
            assertTrue(files.list().isEmpty());
            String candidate = mapper.writeValueAsString(Map.of("expectedRevision", snapshot.revision(), "projects", snapshot.document().projects()));
            var preflight = operations.postJson("/api/operations/configuration/preflight", csrf, candidate);
            assertEquals(200, preflight.statusCode());
            String applyBody = mapper.writeValueAsString(Map.of("expectedRevision", snapshot.revision(), "projects", snapshot.document().projects(),
                    "candidateHash", mapper.readTree(preflight.body()).path("candidateHash").stringValue()));
            assertEquals(200, operations.postJson("/api/operations/configuration/apply", csrf, applyBody).statusCode());
            assertEquals("AUTHENTICATED:asdfgh", store.snapshot().document().actor());
            assertEquals(200, operations.get("/api/operations/configuration").statusCode());
            assertEquals(403, viewer.postJson("/api/operations/configuration/preflight", capture(SESSION_CSRF, viewer.get("/api/auth/session").body()), candidate).statusCode());
            assertEquals(302, operations.post("/logout", capture(SESSION_CSRF, session.body())).statusCode());
            assertEquals(401, operations.get("/api/projects").statusCode());
            Client admin=new Client(root); admin.login("admin",ADMIN_PASSWORD);
            assertEquals(200, admin.get("/api/operations/collection-status").statusCode());
            var adminSession=admin.get("/api/auth/session");
            assertTrue(adminSession.body().contains("\"administrator\":true"));
            assertEquals(200,admin.get("/api/operations/configuration").statusCode());
            String adminCsrf=capture(SESSION_CSRF,adminSession.body());
            assertEquals(403,admin.post("/api/admin/accounts",null).statusCode());
            assertEquals(200,admin.postJson("/api/admin/accounts",adminCsrf,"{\"username\":\"new-viewer\",\"password\":\"Unicode大密码123!\",\"role\":\"VIEWER\"}").statusCode());
            Client newViewer=new Client(root);newViewer.login("new-viewer","Unicode大密码123!");
            assertEquals(200,newViewer.get("/api/projects").statusCode());
            assertEquals(403,newViewer.get("/api/operations/files").statusCode());
            assertEquals(403,viewer.postJson("/api/admin/accounts",capture(SESSION_CSRF,viewer.get("/api/auth/session").body()),"{\"username\":\"bad\",\"password\":\"123456\",\"role\":\"OPERATIONS\"}").statusCode());
            assertEquals(400,admin.deleteJson("/api/admin/accounts/admin",adminCsrf,"{\"confirmed\":true}").statusCode());
            assertEquals(400,admin.deleteJson("/api/admin/accounts/new-viewer",adminCsrf,"{\"confirmed\":false}").statusCode());
            assertEquals(200,admin.deleteJson("/api/admin/accounts/new-viewer",adminCsrf,"{\"confirmed\":true}").statusCode());
            assertEquals(401,newViewer.get("/api/projects").statusCode());
            assertEquals(400,admin.postJson("/api/admin/accounts",adminCsrf,"{\"username\":\"new-viewer\",\"password\":\"123456\",\"role\":\"VIEWER\"}").statusCode());
            assertFalse(admin.get("/api/admin/accounts").body().contains("password"));
        }
    }

    @Test
    void missingPasswordHashesFailClosed() {
        var app = new SpringApplication(TrialTestApp.class);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                "logging.level.root", "OFF", "spring.main.banner-mode", "off"));
        assertThrows(Exception.class, app::run);
    }

    private static String capture(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        assertTrue(matcher.find(), "Expected token not found");
        return matcher.group(1);
    }

    private static final class Client {
        private final String root;
        private final HttpClient http;
        Client(String root) {
            this.root = root;
            http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        }
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(root + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String csrf) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + path))
                    .POST(HttpRequest.BodyPublishers.ofString(""));
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> postJson(String path, String csrf, String body) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(root + path))
                    .header("Content-Type", "application/json").header("Origin", "https://trial.example")
                    .header("X-SCSJ-Config-Write", "1").header("X-CSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> putJson(String path, String csrf, String body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(root + path)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> putBytes(String path, String csrf, String body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(root + path)).header("Content-Type", "application/octet-stream")
                    .header("Origin", "https://trial.example").PUT(HttpRequest.BodyPublishers.ofString(body));
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> deleteJson(String path,String csrf,String body) throws Exception {
            var request=HttpRequest.newBuilder(URI.create(root+path)).header("Content-Type","application/json")
                    .header("Origin","https://trial.example").method("DELETE",HttpRequest.BodyPublishers.ofString(body));
            if(csrf!=null) request.header("X-CSRF-TOKEN",csrf);
            return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        void login(String username, String password) throws Exception {
            String csrf = capture(LOGIN_CSRF, get("/login").body());
            String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                    + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
            var response = http.send(HttpRequest.newBuilder(URI.create(root + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, response.statusCode());
            assertFalse(response.headers().firstValue("Location").orElse("").contains("error"));
        }
    }
}
