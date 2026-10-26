package cn.datadashboard.local;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** One-time creation of this application's four tables in the existing shared database. */
public final class TitaniaBootstrapMain {
    private static final Set<String> TABLES = Set.of("scsj_fact_input_version", "scsj_material_day_fact",
            "scsj_fact_day_state", "scsj_flyway_schema_history");
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
                logs.reset(); logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
            }
            Path root = Path.of("").toRealPath();
            if (!root.equals(Path.of("/opt/material-perspectives"))) throw new IllegalArgumentException();
            var json = new JsonMapper();
            var credentials = json.readTree(System.in.readAllBytes());
            var source = new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:3306/material_dashboard?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
                    credentials.path("username").stringValue(), credentials.path("password").stringValue());
            var jdbc = new JdbcTemplate(source);
            List<String> existing = jdbc.queryForList("SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema='material_dashboard'", String.class);
            if (existing.stream().anyMatch(TABLES::contains)) throw new IllegalStateException();
            var flyway = Flyway.configure().dataSource(source).table("scsj_flyway_schema_history")
                    .locations("classpath:db/migration").baselineVersion("0").cleanDisabled(true).load();
            flyway.baseline(); // Creates only the separately named history table; never adopts another app's history.
            flyway.migrate();
            if (!flyway.validateWithResult().validationSuccessful) throw new IllegalStateException();
            List<String> after = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='material_dashboard'", String.class);
            if (!after.containsAll(TABLES) || after.size() != existing.size() + 4) throw new IllegalStateException();
            Path state = root.resolve(".local/d218-runtime");
            Files.createDirectories(state);
            new ProjectConfigurationStore(root, state.resolve("configuration")).initialize(List.of());
            var plan = json.createObjectNode().put("workspaceRoot", root.toString())
                    .put("credentialsPath", state.resolve("db-credentials.json").toString())
                    .put("schemaName", "material_dashboard").put("runId", "titania-runtime")
                    .put("expectedAccountId", "0"); // No project or business account is fabricated.
            plan.putArray("autoDays"); plan.putArray("manualDays");
            json.writeValue(state.resolve("connection.json"), plan);
            System.out.println("TITANIA_FOUR_TABLES_CREATED_AND_MIGRATIONS_VALIDATED_NO_BUSINESS_DATA_IMPORTED");
        } catch (Exception failure) {
            System.err.println("TITANIA_BOOTSTRAP_STOPPED_RETAIN_PARTIAL_STATE_DO_NOT_RERUN");
            System.exit(2);
        }
    }
}
