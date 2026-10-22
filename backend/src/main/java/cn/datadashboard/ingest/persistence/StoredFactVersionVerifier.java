package cn.datadashboard.ingest.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Pattern;

@Component
final class StoredFactVersionVerifier {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    void verify(
            PreparedFactBatch incoming,
            StoredFactVersionSnapshot stored,
            List<StoredMaterialFactSnapshot> storedFacts
    ) {
        if (!incoming.accountId().equals(stored.accountId())
                || !incoming.statDate().equals(stored.statDate())
                || !incoming.contentHash().equals(stored.contentHash())) {
            throw new ReadbackVerificationException(
                    "Stored version identity does not match the idempotent input identity");
        }
        if (stored.rowCount() < 0
                || stored.manifestPath() == null || stored.manifestPath().isBlank()
                || stored.materialPath() == null || stored.materialPath().isBlank()
                || stored.manifestSizeBytes() < 0
                || stored.materialSizeBytes() < 0
                || !validSha256(stored.contentHash())
                || !validSha256(stored.manifestSha256())
                || !validSha256(stored.materialSha256())) {
            throw new ReadbackVerificationException("Stored version evidence is incomplete or invalid");
        }
        if (storedFacts.size() != stored.rowCount()) {
            throw new ReadbackVerificationException("Stored fact row count does not match its version record");
        }

        Set<String> materialIds = new HashSet<>();
        ArrayNode storedRows = mapper.createArrayNode();
        for (int index = 0; index < storedFacts.size(); index++) {
            StoredMaterialFactSnapshot fact = storedFacts.get(index);
            int expectedRowNumber = index + 1;
            if (!stored.accountId().equals(fact.accountId())
                    || !stored.statDate().equals(fact.statDate())
                    || fact.materialId() == null || fact.materialId().isBlank()
                    || !materialIds.add(fact.materialId())
                    || fact.sourceRowNumber() != expectedRowNumber
                    || fact.rawFactJson() == null
                    || !validSha256(fact.rowSha256())
                    || !fact.rowSha256().equals(sha256(fact.rawFactJson()))) {
                throw new ReadbackVerificationException("Stored fact payload integrity verification failed");
            }
            try {
                JsonNode parsed = mapper.readTree(fact.rawFactJson());
                if (parsed == null || !parsed.isObject()) {
                    throw new ReadbackVerificationException("Stored fact payload is not a JSON object");
                }
                storedRows.add(parsed);
            } catch (JacksonException exception) {
                throw new ReadbackVerificationException("Stored fact payload is not valid unambiguous JSON");
            }
        }
        try {
            String reconstructedContentHash = incoming.sourceType() == FactSourceType.MANUAL_HISTORY
                    // The manual v1 evidence preserves Python-canonical numeric spelling.
                    // Parsing validates JSON; re-serialization must not change its byte hash.
                    ? sha256("[" + String.join(",", storedFacts.stream()
                            .map(StoredMaterialFactSnapshot::rawFactJson).toList()) + "]")
                    : sha256(mapper.writeValueAsBytes(sortRecursively(storedRows)));
            if (!stored.contentHash().equals(reconstructedContentHash)) {
                throw new ReadbackVerificationException(
                        "Stored fact rows do not reconstruct the stored content hash");
            }
        } catch (JacksonException exception) {
            throw new ReadbackVerificationException("Stored fact content hash could not be reconstructed");
        }
    }

    private static boolean validSha256(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey()))
                    .forEach(entry -> sorted.set(entry.getKey(), sortRecursively(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            ((ArrayNode) node).elements().forEach(element -> sorted.add(sortRecursively(element)));
            return sorted;
        }
        return node.deepCopy();
    }
}

record StoredFactVersionSnapshot(
        String accountId,
        LocalDate statDate,
        String contentHash,
        FactVersionDisposition disposition,
        int rowCount,
        String manifestPath,
        long manifestSizeBytes,
        String manifestSha256,
        String materialPath,
        long materialSizeBytes,
        String materialSha256
) {
}

record StoredMaterialFactSnapshot(
        String accountId,
        LocalDate statDate,
        String materialId,
        int sourceRowNumber,
        String rawFactJson,
        String rowSha256
) {
}
