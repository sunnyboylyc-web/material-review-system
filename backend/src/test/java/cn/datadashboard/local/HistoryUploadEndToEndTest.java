package cn.datadashboard.local;

import cn.datadashboard.api.HistoryUploadController;
import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.history.HistoryUploadService;
import cn.datadashboard.ingest.persistence.*;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Explicit opt-in, fresh schema only. No business schema writes or cleanup. */
@EnabledIfEnvironmentVariable(named="SCSJ_HISTORY_IT_ENABLED", matches="true")
class HistoryUploadEndToEndTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACCOUNT="1000000000086001";
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.header("Host","127.0.0.1:18080").header("Origin","http://127.0.0.1:5180")
                .header("X-SCSJ-History-Write","1").with(r->{r.setRemoteAddr("127.0.0.1");return r;});
    }
    private static JsonNode response(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        var response=mvc.perform(local(request)).andReturn().getResponse();
        assertThat(response.getStatus()).as("HTTP status (response intentionally not logged)").isEqualTo(200);
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** Legacy unresolved uploads are fixtures, not a reason to bypass the current one-upload guard. */
    private static String legacyClone(Path workspace, Path legacyWorkspace, JsonNode original) throws Exception {
        String id=UUID.randomUUID().toString().replace("-", "");
        Path from=workspace.resolve(".local/history-uploads").resolve(original.path("uploadId").stringValue());
        Path to=legacyWorkspace.resolve(".local/history-uploads").resolve(id);
        Files.createDirectories(to);
        try(var paths=Files.walk(from)) {
            for(Path path:paths.filter(p->!p.equals(from)).toList()) {
                Path target=to.resolve(from.relativize(path));
                if(Files.isDirectory(path)) Files.createDirectory(target); else Files.copy(path,target);
            }
        }
        ObjectNode preview=(ObjectNode)JSON.readTree(Files.readAllBytes(to.resolve("preview.json")));
        preview.put("uploadId",id);
        byte[] bytes=JSON.writeValueAsBytes(preview); Files.write(to.resolve("preview.json"),bytes);
        ObjectNode envelope=(ObjectNode)JSON.readTree(Files.readAllBytes(to.resolve("state.json")));
        ObjectNode state=(ObjectNode)envelope.get("state");
        state.put("uploadId",id).put("previewHash",hash(bytes));
        envelope.put("sha256",hash(JSON.writeValueAsBytes(state)));
        Files.write(to.resolve("state.json"),JSON.writeValueAsBytes(envelope));
        return id;
    }

    @Test void realConverterControllerMysqlReadbackCleanupRestartAndNoOverwrite() throws Exception {
        if(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs){
            logs.reset();logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        java.util.logging.LogManager.getLogManager().reset();
        Path cwd=Path.of("").toRealPath();
        Path root=Files.exists(cwd.resolve("START_HERE.md"))?cwd:cwd.getParent();
        assertThat(Files.exists(root.resolve("START_HERE.md"))).isTrue();
        String port=System.getenv("SCSJ_IT_MYSQL_PORT");assertThat(port).matches("[0-9]{4,5}");
        String username=System.getenv("SCSJ_IT_MYSQL_USERNAME"), password=System.getenv("SCSJ_IT_MYSQL_PASSWORD");
        assertThat(username!=null && password!=null).isTrue();
        String schema="SCSJ_HISTORY_IT_"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        String base="jdbc:mysql://127.0.0.1:"+port+"/";
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        var admin=new JdbcTemplate(new DriverManagerDataSource(base+options,username,password));
        Path data=LocalFactRepairMain.decodeDataDirectory(admin.queryForObject("SELECT HEX(@@datadir)",String.class)).toRealPath();
        assertThat(data.startsWith(root.resolve(".local").toRealPath())).isTrue();
        admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var source=new DriverManagerDataSource(base+schema+options,username,password);
        Flyway.configure().table("scsj_flyway_schema_history").dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        var jdbc=new JdbcTemplate(source);
        Path run=root.resolve(".local/history-integration").resolve(schema);
        Path workspace=run.resolve("workspace");Files.createDirectories(workspace.resolve(".local"));
        for(String file:List.of("contracts/manual-history-upload-v1.json","contracts/manual-history-workbook-v1.json",
                "tools/manual_history/convert_upload_template.py","tools/manual_history/convert_upload_bridge.py")) {
            Path target=workspace.resolve(file);Files.createDirectories(target.getParent());Files.copy(root.resolve(file),target);
        }
        // Seed one explicitly synthetic, complete empty MCP day to exercise the real cutoff reader.
        Path seed=run.resolve("synthetic-mcp.json");Files.writeString(seed,"[]");String emptyHash=hash("[]".getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
          INSERT INTO scsj_fact_input_version(account_id,stat_date,project_ref,source_type,data_version,content_hash,
          schema_version,catalog_version,manifest_path,manifest_size_bytes,manifest_sha256,
          material_path,material_size_bytes,material_sha256,disposition,row_count,run_id)
          VALUES(?, '2026-01-10','synthetic','MCP_AUTO','synthetic-seed',?,'qianchuan-material-day-standard-1.0.0',
          'synthetic-seed',?,2,?,?,2,?,'CURRENT',0,'synthetic-seed')
          """,ACCOUNT,emptyHash,seed.toString(),emptyHash,seed.toString(),emptyHash);
        long seedId=jdbc.queryForObject("SELECT id FROM scsj_fact_input_version WHERE account_id=?",Long.class,ACCOUNT);
        jdbc.update("INSERT INTO scsj_fact_day_state(account_id,stat_date,current_input_version_id) VALUES(?,'2026-01-10',?)",ACCOUNT,seedId);
        var config=new ProjectConfigurationStore(workspace,workspace.resolve(".local/configuration"));
        config.initialize(List.of(new ProjectConfigurationStore.Project("synthetic","合成历史验收",ACCOUNT,List.of("123456"),true),
                new ProjectConfigurationStore.Project("no-mcp","无采集合成项目","1000000000086002",List.of("654321"),true)));
        Path python=Path.of(System.getenv("SCSJ_PYTHON_EXECUTABLE"));
        var gateway=new JdbcHistoryFactGateway(source);
        var service=new HistoryUploadService(workspace,config,gateway,python,true);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new HistoryUploadController(service)).build();
        String api="/api/operations/history/projects/synthetic";
        var context=response(mvc,get(api+"/context"));
        assertThat(context.path("firstMcpDate").stringValue()).isEqualTo("2026-01-10");
        assertThat(response(mvc,get("/api/operations/history/projects/no-mcp/context")).path("firstMcpDate").isNull()).isTrue();
        byte[] template=mvc.perform(local(get("/api/operations/history/template"))).andReturn().getResponse().getContentAsByteArray();
        assertThat(hash(template)).isEqualTo(hash(Files.readAllBytes(root.resolve("backend/src/main/resources/templates/manual-history.xlsx"))));
        byte[] upload=Files.readAllBytes(Path.of(System.getenv("SCSJ_HISTORY_TEST_XLSX")));
        var preview=response(mvc,post(api+"/preview").contentType("application/octet-stream").content(upload));
        assertThat(preview.path("status").stringValue()).isEqualTo("PREVIEW_READY");
        assertThat(preview.path("rowCount").intValue()).isEqualTo(2);
        String uploadApi=api+"/uploads/"+preview.path("uploadId").stringValue();
        String confirm=JSON.writeValueAsString(java.util.Map.of("previewHash",preview.path("previewHash").stringValue(),"confirmAccount",true,"confirmCompleteDays",true));
        var occupied=mvc.perform(local(post(api+"/preview").contentType("application/octet-stream").content(upload)))
                .andReturn().getResponse();
        assertThat(occupied.getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(occupied.getContentAsString(StandardCharsets.UTF_8)).path("code").stringValue())
                .isEqualTo("UPLOAD_SLOT_OCCUPIED");
        // Retain a legacy competing upload in a separate synthetic workspace sharing this test database.
        // Its failure evidence must remain intact and must not block the later fresh-upload assertions.
        Path legacyWorkspace=run.resolve("legacy-workspace");
        String otherId=legacyClone(workspace,legacyWorkspace,preview);
        var legacyService=new HistoryUploadService(legacyWorkspace,config,gateway,python,true);
        var otherPreview=legacyService.get("synthetic",otherId);
        HistoryFactGateway crashGateway=new HistoryFactGateway() {
            boolean crash=true;
            public Context inspect(String account){return gateway.inspect(account);}
            public FactStoreResult importNewDay(PreparedFactBatch batch){var saved=gateway.importNewDay(batch);if(crash){crash=false;throw new AssertionError("synthetic crash after database commit, before version persistence");}return saved;}
            public void verify(PreparedFactBatch batch,long version){gateway.verify(batch,version);}
            public java.util.OptionalLong recoverCurrentDay(PreparedFactBatch batch){return gateway.recoverCurrentDay(batch);}
        };
        var crashing=new HistoryUploadService(workspace,config,crashGateway,python,true);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,()->crashing.apply("synthetic",preview.path("uploadId").stringValue(),preview.path("previewHash").stringValue(),true,true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scsj_fact_input_version",Integer.class)).isEqualTo(2);
        assertThat(service.get("synthetic",preview.path("uploadId").stringValue()).path("status").stringValue()).isEqualTo("INTERRUPTED");
        assertThat(legacyService.apply("synthetic",otherId,otherPreview.path("previewHash").stringValue(),true,true).path("status").stringValue()).isEqualTo("IMPORT_FAILED");
        assertThat(Files.exists(legacyWorkspace.resolve(".local/history-uploads").resolve(otherId).resolve("upload.xlsx"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scsj_fact_input_version",Integer.class)).isEqualTo(2);
        var complete=response(mvc,post(uploadApi+"/apply").contentType("application/json").content(confirm));
        assertThat(complete.path("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(complete.path("sourceFileState").stringValue()).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scsj_fact_input_version",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scsj_material_day_fact",Integer.class)).isEqualTo(2);
        var snapshot=new JdbcDashboardSnapshotReader(source).read(ACCOUNT);
        assertThat(snapshot.days()).hasSize(3);
        assertThat(snapshot.days().stream().anyMatch(d->d.date().equals(LocalDate.of(2026,1,2)))).isFalse();
        assertThat(snapshot.facts()).hasSize(2);
        // Recreate the service to ensure saved records and machine evidence survive source deletion.
        mvc=MockMvcBuilders.standaloneSetup(new HistoryUploadController(new HistoryUploadService(workspace,config,gateway,python,true))).build();
        assertThat(response(mvc,get(uploadApi)).path("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(response(mvc,post(uploadApi+"/apply").contentType("application/json").content(confirm)).path("status").stringValue()).isEqualTo("COMPLETED");
        var repeated=response(mvc,post(api+"/preview").contentType("application/octet-stream").content(upload));
        assertThat(repeated.path("status").stringValue()).isEqualTo("VALIDATION_FAILED");
        String cancel=JSON.writeValueAsString(java.util.Map.of("previewHash",repeated.path("previewHash").stringValue()));
        var discarded=response(mvc,post(api+"/uploads/"+repeated.path("uploadId").stringValue()+"/discard").contentType("application/json").content(cancel));
        assertThat(discarded.path("sourceFileState").stringValue()).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scsj_fact_input_version",Integer.class)).isEqualTo(3);
        int unauthorized=mvc.perform(post(api+"/preview").contentType("application/octet-stream").content(upload)).andReturn().getResponse().getStatus();
        assertThat(unauthorized).isEqualTo(403);
        JSON.writeValue(run.resolve("receipt.json"),java.util.Map.of("status","PASSED","syntheticOnly",true,
                "schema",schema,"versions",3,"factRows",2,"mcpSeedDays",1,"historyDays",2,
                "sourceCopyDeleted",true,"restartVerified",true,"businessDatabaseTouched",false));
        System.out.println("HISTORY_END_TO_END_PASSED receipt="+run.resolve("receipt.json"));
    }
}
