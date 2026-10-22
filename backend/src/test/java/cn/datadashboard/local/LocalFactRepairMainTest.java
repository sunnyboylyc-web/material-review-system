package cn.datadashboard.local;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Argument/evidence guards only; all inputs synthetic and no database is opened by these cases. */
class LocalFactRepairMainTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path workspace;
    private Path local;
    private Path config;
    private Path preflight;
    private ObjectNode ready;

    @BeforeEach
    void prepareSyntheticPrivateEvidence() throws Exception {
        local = Files.createDirectory(workspace.resolve(".local"));
        config = local.resolve("config.json");
        ObjectNode configJson = JSON.createObjectNode().put("workspaceRoot", workspace.toString())
                .put("credentialsPath", local.resolve("never-read-secret.json").toString())
                .put("schemaName", "SCSJ_LOCAL_SYNTHETIC").put("runId", "synthetic")
                .put("expectedAccountId", "1000000000000001");
        configJson.putArray("autoDays");
        configJson.putArray("manualDays");
        JSON.writeValue(config, configJson);
        ready = JSON.createObjectNode().put("schemaVersion", "local-source-preflight-1.0.0")
                .put("status", "SOURCE_READY").put("statDate", "2026-09-30")
                .put("sourceType", "MCP_AUTO").put("contentHash", "a".repeat(64)).put("rowCount", 1);
        ready.putArray("evidence").addObject().put("role", "importConfig").put("absolutePath", config.toString())
                .put("sizeBytes", Files.size(config)).put("sha256", LocalFactVerifyMain.sha(Files.readAllBytes(config)));
        preflight = local.resolve("ready.json");
        JSON.writeValue(preflight, ready);
    }

    @Test
    void requiresExactlySixArgumentsDateHashCurrentExpectationAndExplicitApply() {
        String[] valid = {"config", "2026-09-30", "receipt", "A".repeat(64), "MISSING", "--apply"};
        assertThat(LocalFactRepairMain.arguments(valid).preflightSha()).isEqualTo("a".repeat(64));
        for (String[] invalid : List.of(new String[]{}, new String[]{"config"},
                new String[]{"config", "2026-09-30", "receipt", "a".repeat(64), "MISSING"},
                new String[]{"config", "2026-09-30", "receipt", "a".repeat(64), "MISSING", "--dry-run"},
                new String[]{"config", "2026-09-30", "receipt", "a".repeat(64), "MISSING", "--apply", "all"},
                new String[]{"config", "2026-9-30", "receipt", "a".repeat(64), "MISSING", "--apply"},
                new String[]{"config", "2026-02-30", "receipt", "a".repeat(64), "MISSING", "--apply"},
                new String[]{"config", "2026-09-30", "receipt", "wrong", "MISSING", "--apply"},
                new String[]{"config", "2026-09-30", "receipt", "a".repeat(64), "latest", "--apply"})) {
            assertThatThrownBy(() -> LocalFactRepairMain.arguments(invalid)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void changedReceiptIsRejectedBeforeAnySourceOrCredentialAccess() throws Exception {
        String[] args = arguments();
        Files.writeString(preflight, "malformed secret source-account");
        JsonNode result = execute(args);
        assertThat(result.get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(result.get("errorCode").stringValue()).isEqualTo("PREFLIGHT_BYTES_CHANGED");
        assertNotAttempted(result);
        assertThat(result.toString()).doesNotContain("secret source-account");
    }

    @Test
    void changedSourceOrConfigurationHashIsRejectedBeforeExecution() throws Exception {
        Files.writeString(config, "changed credentials and account");
        JsonNode result = execute(arguments());
        assertThat(result.get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(result.get("errorCode").stringValue()).isEqualTo("SOURCE_EVIDENCE_CHANGED");
        assertNotAttempted(result);
        assertThat(result.toString()).doesNotContain("changed credentials and account");
    }

    @Test
    void wrongDayAndNonReadyReceiptAreNotAcceptedAsAuthorization() throws Exception {
        for (String key : List.of("status", "statDate", "schemaVersion")) {
            ObjectNode changed = ready.deepCopy();
            changed.put(key, "not-applicable");
            JSON.writeValue(preflight, changed);
            JsonNode result = execute(arguments());
            assertThat(result.get("status").stringValue()).isEqualTo("REJECTED");
            assertThat(result.get("errorCode").stringValue()).isEqualTo("PREFLIGHT_NOT_READY_FOR_DAY");
            assertNotAttempted(result);
        }
    }

    @Test
    void forgedReadyForAnUnconfiguredDayCannotFallBackToMultiDayImport() throws Exception {
        JsonNode result = execute(arguments());
        assertThat(result.get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(result.get("errorCode").stringValue()).isEqualTo("FRESH_SOURCE_PREFLIGHT_REJECTED");
        assertNotAttempted(result);
        assertThat(result.get("expectedCurrent").stringValue()).isEqualTo("MISSING");
        assertThat(result.get("actor").stringValue()).isEqualTo("LOCAL_UNAUTHENTICATED");
    }

    @Test
    void pathsOutsideLocalRootRejectWithoutCopyingTheirContents() throws Exception {
        Path external = workspace.resolve("outside-ready.json");
        Files.writeString(external, "do not read secret");
        String[] args = arguments();
        args[2] = external.toString();
        JsonNode result = execute(args);
        assertThat(result.get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(result.get("errorCode").stringValue()).isEqualTo("LOCAL_FILE_REJECTED");
        assertNotAttempted(result);
        assertThat(result.toString()).doesNotContain("do not read secret");
    }

    @Test
    void consoleErrorsAreFixedAndEarlierReceiptsAreNeverOverwritten() throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = LocalFactRepairMain.run(new String[]{"secret-untrusted-value"}, workspace,
                new PrintStream(out), new PrintStream(err));
        assertThat(exit).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEqualTo("Local single-day repair stopped: arguments, local paths or receipt preflight failed." + System.lineSeparator());
        var first = LocalFactRepairMain.execute(arguments(), workspace, LocalFactRepairMain.Hooks.NONE);
        byte[] firstBytes = Files.readAllBytes(first.receiptPath());
        var second = LocalFactRepairMain.execute(arguments(), workspace, LocalFactRepairMain.Hooks.NONE);
        assertThat(second.receiptPath()).isNotEqualTo(first.receiptPath());
        assertThat(Files.readAllBytes(first.receiptPath())).containsExactly(firstBytes);
    }

    @Test
    void datadirHexUsesTheNativeWindowsCharsetAndRejectsMalformedBytes() throws Exception {
        Charset nativeCharset = System.getProperty("os.name", "").startsWith("Windows")
                ? Charset.forName(System.getProperty("native.encoding")) : StandardCharsets.UTF_8;
        String path = workspace.resolve("数据库").toString();
        String hex = HexFormat.of().formatHex(path.getBytes(nativeCharset));
        assertThat(LocalFactRepairMain.decodeDataDirectory(hex)).isEqualTo(Path.of(path));
        for (String malformed : List.of("not-hex", "", "00")) {
            assertThatThrownBy(() -> LocalFactRepairMain.decodeDataDirectory(malformed)).isInstanceOf(Exception.class);
        }
    }

    private String[] arguments() throws Exception {
        return new String[]{config.toString(), "2026-09-30", preflight.toString(),
                LocalFactVerifyMain.sha(Files.readAllBytes(preflight)), "MISSING", "--apply"};
    }

    private JsonNode execute(String[] arguments) throws Exception {
        var result = LocalFactRepairMain.execute(arguments, workspace, LocalFactRepairMain.Hooks.NONE);
        return JSON.readTree(Files.readAllBytes(result.receiptPath()));
    }

    private static void assertNotAttempted(JsonNode result) {
        assertThat(result.get("transactionReturned").booleanValue()).isFalse();
        for (String key : List.of("rowCount", "contentHash", "transactionOutcome", "inputVersionId", "disposition", "independentVerification")) {
            assertThat(result.get(key).isNull()).as(key).isTrue();
        }
        assertThat(result.has("target")).isFalse();
        assertThat(result.toString()).doesNotContain("1000000000000001", "never-read-secret");
    }
}
