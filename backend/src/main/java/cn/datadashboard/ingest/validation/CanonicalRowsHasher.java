package cn.datadashboard.ingest.validation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;

final class CanonicalRowsHasher {

    private final JsonMapper mapper;

    CanonicalRowsHasher(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String sha256(JsonNode rows) throws JacksonException {
        JsonNode sorted = sortRecursively(rows);
        return sha256Hex(mapper.writeValueAsBytes(sorted));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
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
