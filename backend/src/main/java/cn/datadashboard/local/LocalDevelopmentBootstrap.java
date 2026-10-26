package cn.datadashboard.local;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** Explicit, loopback-only empty development database. Never copies production data. */
public final class LocalDevelopmentBootstrap {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("NO_ARGUMENTS_ALLOWED");
        Path root = Path.of("").toRealPath();
        Path local = root.resolve(".local").toRealPath();
        Path credentials = local.resolve("mysql-dev-620261019/credentials.json");
        var json = new JsonMapper();
        var secret = json.readTree(Files.readAllBytes(credentials));
        if (secret.path("port").intValue() != 13480) throw new IllegalArgumentException("LOCAL_TEST_PORT_REQUIRED");
        String username = secret.path("username").stringValue(), password = secret.path("password").stringValue();
        String url = "jdbc:mysql://127.0.0.1:13480/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
        var admin = new JdbcTemplate(new DriverManagerDataSource(url + options, username, password));
        Path data = LocalFactRepairMain.decodeDataDirectory(admin.queryForObject("SELECT HEX(@@datadir)", String.class)).toRealPath();
        if (!data.startsWith(local.resolve("mysql-dev-620261019"))) throw new IllegalArgumentException("LOCAL_TEST_DATADIR_REQUIRED");
        Path run = local.resolve("local-development");
        Files.createDirectory(run); // Existing attempts are retained; never overwrite or silently reuse.
        String schema = "SCSJ_LOCAL_DEV_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        Files.writeString(run.resolve("schema.txt"), schema);
        admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var source = new DriverManagerDataSource(url + schema + options, username, password);
        Flyway.configure().table("scsj_flyway_schema_history").dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        Path config = run.resolve("configuration");
        new ProjectConfigurationStore(root, config).initialize(List.of(
                new ProjectConfigurationStore.Project("local-development", "本地开发项目（非云端数据）",
                        "99999999999999990001", List.of("99999999999999990002"), true)));
        var plan = json.createObjectNode().put("workspaceRoot", root.toString())
                .put("credentialsPath", credentials.toString()).put("schemaName", schema)
                .put("runId", "local-development").put("expectedAccountId", "99999999999999990001");
        plan.putArray("autoDays"); plan.putArray("manualDays");
        json.writeValue(run.resolve("connection.json"), plan);
        Files.writeString(run.resolve("README.txt"), "Local empty development database; no production facts. Collection disabled.\n");
        System.out.println("LOCAL_DEVELOPMENT_DATABASE_READY_NO_PRODUCTION_DATA");
    }
}
