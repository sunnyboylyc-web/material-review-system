package cn.datadashboard.local;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryDashboardMainTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    @Test void recoveryWebLoginAndWriteRejection() throws Exception {
        // Authentication/static routing must not depend on an omitted historical UI build.
        var staticRoot = java.nio.file.Files.createDirectories(workspace.resolve("static"));
        java.nio.file.Files.writeString(staticRoot.resolve("index.html"),
                "<!doctype html><html><body><div id=\"app\">Synthetic recovery test</div></body></html>");
        var app = new SpringApplication(RecoveryDashboardMain.RecoveryWebConfiguration.class);
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                "spring.web.resources.static-locations", staticRoot.toUri().toString(),
                "logging.level.root", "OFF", "spring.main.banner-mode", "off",
                "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"));
        java.nio.file.Files.createDirectories(workspace.resolve(".local"));
        var store = new cn.datadashboard.configuration.ProjectConfigurationStore(workspace, workspace.resolve(".local/configuration"));
        store.initialize(java.util.List.of(new cn.datadashboard.configuration.ProjectConfigurationStore.Project(
                "synthetic", "test", "10000001", java.util.List.of("20000001"), true)));
        app.addInitializers(c -> {
            c.getBeanFactory().registerSingleton("recoveryUsers",
                    RecoveryDashboardMain.users(new BCryptPasswordEncoder().encode("synthetic-password")));
            c.getBeanFactory().registerSingleton("configurationController",
                    new cn.datadashboard.api.ConfigurationController(store, false,
                            new cn.datadashboard.configuration.AnchorResolver((a,p,n) -> { throw new IllegalStateException("NO_NETWORK"); }),
                            new cn.datadashboard.security.TrialRequestGuard("https://dashboard.example.com")));
        });
        try (var context = app.run()) {
            String base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            assertTrue(login.body().contains("管理员只读恢复版"));
            assertFalse(login.body().contains("name=\"remember-me\""));
            var matcher = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(login.body());
            assertTrue(matcher.find());
            String form = "username=admin&password=synthetic-password&_csrf=" + java.net.URLEncoder.encode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(302, client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            var session = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, session.statusCode());
            var json = new tools.jackson.databind.json.JsonMapper().readTree(session.body());
            assertTrue(json.get("recoveryReadOnly").asBoolean());
            assertTrue(json.get("operations").asBoolean());
            var configuration = client.send(HttpRequest.newBuilder(URI.create(base + "/api/operations/configuration")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, configuration.statusCode());
            assertFalse(new tools.jackson.databind.json.JsonMapper().readTree(configuration.body()).get("writeEnabled").asBoolean());
            var page = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("id=\"app\""));
            for (String path : List.of("/api/operations/configuration/apply", "/api/admin/accounts", "/api/operations/files")) {
                assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("X-CSRF-TOKEN", json.get("csrfToken").asString())
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }
    @Test void onlyAdminReadRoutesAndNoBootstrapUsers() throws Exception {
        String hash = new BCryptPasswordEncoder().encode("synthetic-only-password");
        var users = RecoveryDashboardMain.users(hash);
        assertThrows(Exception.class, () -> users.loadUserByUsername("123456"));
        assertThrows(Exception.class, () -> users.loadUserByUsername("asdfgh"));
        assertThrows(IllegalArgumentException.class, () -> RecoveryDashboardMain.users("plaintext"));
        var app = new SpringApplication(RecoveryDashboardMain.RecoveryConfiguration.class);
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                "logging.level.root", "OFF", "spring.main.banner-mode", "off",
                "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"));
        app.addInitializers(c -> {
            c.getBeanFactory().registerSingleton("recoveryUsers", users);
            c.getBeanFactory().registerSingleton("dashboard", new cn.datadashboard.api.DashboardController(
                    cn.datadashboard.api.ProjectDirectory.single("10000001"), a -> null));
        });
        try (var context = app.run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newHttpClient();
            String origin = "http://127.0.0.1:" + port;
            String auth = "Basic " + Base64.getEncoder().encodeToString("admin:synthetic-only-password".getBytes());
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(origin + "/api/projects")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(origin + "/api/projects")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            for (String path : List.of("/api/admin/accounts", "/api/operations/configuration", "/api/projects")) {
                assertTrue(Set.of(401, 403).contains(client.send(HttpRequest.newBuilder(URI.create(origin + path)).header("Authorization", auth).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode()));
            }
            assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(origin + "/api/admin/accounts")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
