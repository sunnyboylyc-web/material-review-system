package cn.datadashboard.ingest.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlMigrationContractTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE `([^`]+)` \\((.*?)\\) ENGINE=InnoDB.*?COMMENT='([^']+)'",
            Pattern.DOTALL);

    @Test
    void allTablesUsePrefixAndEveryColumnHasACommentAndTimestamps() throws Exception {
        String migration = readMigration();
        Matcher matcher = CREATE_TABLE.matcher(migration);
        List<String> tables = new ArrayList<>();
        while (matcher.find()) {
            String table = matcher.group(1);
            String body = matcher.group(2);
            tables.add(table);
            assertThat(table).startsWith("SCSJ_");
            assertThat(matcher.group(3)).isNotBlank();
            assertThat(body).contains("`created_at`").contains("`updated_at`");
            for (String line : body.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.startsWith("PRIMARY KEY")) {
                    break;
                }
                if (trimmed.startsWith("`")) {
                    assertThat(trimmed)
                            .as("column definition must include COMMENT: %s.%s", table, trimmed)
                            .contains(" COMMENT '");
                }
            }
        }
        assertThat(tables).containsExactly(
                "SCSJ_FACT_INPUT_VERSION",
                "SCSJ_MATERIAL_DAY_FACT",
                "SCSJ_FACT_DAY_STATE");
    }

    @Test
    void migrationDeclaresIsolationIdempotencyJsonAndEnumSemantics() throws Exception {
        String migration = readMigration();
        assertThat(migration)
                .contains("UNIQUE KEY `UK_SCSJ_INPUT_ACCOUNT_DAY_HASH` (`account_id`, `stat_date`, `content_hash`)")
                .contains("MCP_AUTO=自动MCP采集")
                .contains("MANUAL_HISTORY=人工历史原件")
                .contains("CURRENT=当前事实")
                .contains("PREVIOUS=上一回滚版")
                .contains("ISOLATED=第三种及以后不同内容")
                .contains("CHECK (JSON_VALID(`raw_fact_json`))");
    }

    @Test
    void jdbcStoreMethodRequiresOneRollbackCapableTransaction() throws Exception {
        Method method = JdbcFactImportStore.class.getMethod("importAtomically", PreparedFactBatch.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    private static String readMigration() throws Exception {
        try (var stream = MysqlMigrationContractTest.class.getResourceAsStream(
                "/db/migration/V1__create_versioned_material_facts.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
