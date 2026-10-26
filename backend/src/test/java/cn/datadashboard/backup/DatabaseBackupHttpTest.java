package cn.datadashboard.backup;

import cn.datadashboard.api.DatabaseBackupController;
import cn.datadashboard.security.TrialRequestGuard;
import cn.datadashboard.security.TrialSecurityConfiguration;
import cn.datadashboard.security.TrialSessionController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseBackupHttpTest {
    @TempDir Path workspace;
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({TrialSecurityConfiguration.class, TrialSessionController.class})
    static class TestApp { }
    static final String API = "/api/operations/database-backup", GENERATION = "c".repeat(32);
    static final String PASSWORD = "Only-Synthetic-Password-123!";
    static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    @Test void httpRolesErrorsAndStreamUseExistingAuthenticatedBoundary() throws Exception {
        Path root = workspace.resolve("backups"), sql = root.resolve("generations").resolve(GENERATION).resolve("business.sql");
        byte[] body = "synthetic-only-dump".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(sql.getParent()); Files.write(sql, body);
        var json = JsonMapper.builder().build();
        Files.writeString(root.resolve("current.json"), json.writeValueAsString(Map.of(
                "schemaVersion", 1, "generation", GENERATION, "database", "SCSJ_LOCAL_CLOUD",
                "createdAt", "2026-10-18T15:00:00Z", "bytes", body.length,
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),
                "validation", "DUMP_EXIT_AND_HASH_READBACK", "restoreTested", false)));
        var encoder = new BCryptPasswordEncoder(); var app = new SpringApplication(TestApp.class);
        app.setLogStartupInfo(false);
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("databaseBackupController",
                new DatabaseBackupController(new WeeklyDatabaseBackupStore(root), new TrialRequestGuard("https://test.example"))
                        .withCustom(new WeeklyDatabaseBackupStore(root), new PeriodBackupService((a,b)->0,
                                java.time.Clock.fixed(java.time.Instant.parse("2026-10-22T01:00:00Z"), java.time.ZoneId.of("Asia/Shanghai"))))));
        app.setDefaultProperties(Map.of(
                "server.address", "127.0.0.1", "server.port", "0", "spring.main.banner-mode", "off",
                "logging.level.root", "OFF", "scsj.auth.state-directory", workspace.resolve("auth").toString(),
                "scsj.auth.viewer-password-hash", encoder.encode(PASSWORD),
                "scsj.auth.operations-password-hash", encoder.encode(PASSWORD),
                "scsj.auth.admin-password-hash", encoder.encode(PASSWORD)));
        try (var context = app.run()) {
            String server = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            var anonymous = new Client(server);
            assertEquals(401, anonymous.get(API).statusCode());
            assertEquals(401, anonymous.get(API + "/download?generation=" + GENERATION).statusCode());
            var viewer = new Client(server); viewer.login("123456");
            assertEquals(403, viewer.get(API).statusCode());
            assertEquals(403, viewer.get(API + "/download?generation=" + GENERATION).statusCode());
            for (String user : List.of("asdfgh", "admin")) {
                var permitted = new Client(server); permitted.login(user);
                var status = permitted.get(API); assertEquals(200, status.statusCode());
                assertEquals("no-store", status.headers().firstValue("Cache-Control").orElseThrow());
                assertTrue(json.readTree(status.body()).path("available").booleanValue());
                assertFalse(status.body().contains(workspace.toString()));
                var download = permitted.get(API + "/download?generation=" + GENERATION);
                assertEquals(200, download.statusCode()); assertEquals(new String(body, StandardCharsets.UTF_8), download.body());
                assertEquals("no", download.headers().firstValue("X-Accel-Buffering").orElseThrow());
                assertEquals("nosniff", download.headers().firstValue("X-Content-Type-Options").orElseThrow());
                assertEquals(409, permitted.get(API + "/download?generation=" + "d".repeat(32)).statusCode());
                assertEquals(400, permitted.get(API + "/download?generation=invalid").statusCode());
                assertEquals(404, permitted.get(API + "/run").statusCode());
                assertEquals(200, permitted.get(API + "/custom").statusCode());
                assertEquals(200, permitted.get(API + "/custom/download?generation=" + GENERATION).statusCode());
                if (user.equals("asdfgh")) {
                    String data="{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-07\"}";
                    var session=json.readTree(permitted.get("/api/auth/session").body());
                    String token=session.path("csrfToken").stringValue();
                    assertEquals(403, permitted.post(API+"/custom",data,null,"https://test.example").statusCode());
                    assertEquals(403, permitted.post(API+"/custom",data,token,"https://other.example").statusCode());
                    assertEquals(400, permitted.post(API+"/custom",data.replace("2026-10-07","2026-10-22"),token,"https://test.example").statusCode());
                    assertEquals(202, permitted.post(API+"/custom",data,token,"https://test.example").statusCode());
                    assertEquals(409, permitted.post(API+"/custom",data,token,"https://test.example").statusCode());
                }
            }
            var operator = new Client(server); operator.login("asdfgh");
            Files.writeString(root.resolve("current.json"), "private-detail-must-not-escape");
            var invalid = operator.get(API); assertEquals(503, invalid.statusCode());
            assertEquals("{\"error\":\"DATABASE_BACKUP_UNAVAILABLE\"}", invalid.body());
        }
    }
    static final class Client {
        final String root;
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        Client(String root) { this.root = root; }
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(root + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body, String token, String origin) throws Exception {
            var request=HttpRequest.newBuilder(URI.create(root+path)).header("Content-Type","application/json").header("Origin",origin);
            if (token!=null) request.header("X-CSRF-TOKEN",token);
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        void login(String user) throws Exception {
            var matcher = CSRF.matcher(get("/login").body()); assertTrue(matcher.find());
            String data = "username=" + user + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                    + "&_csrf=" + URLEncoder.encode(matcher.group(1), StandardCharsets.UTF_8);
            var response = http.send(HttpRequest.newBuilder(URI.create(root + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, response.statusCode()); assertEquals("/", response.headers().firstValue("Location").orElseThrow());
        }
    }
}
