package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import vn.muasamcong.downloader.model.DownloadHints;

public final class ArtifactFingerprintService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArtifactFingerprintService() {
    }

    public static String hashObject(Object value) {
        try {
            return sha256(MAPPER.writeValueAsString(normalize(value)));
        } catch (Exception ex) {
            return sha256(String.valueOf(value));
        }
    }

    public static String hashDownloadHints(DownloadHints hints) {
        if (hints == null) {
            return null;
        }
        return hashObject(Map.ofEntries(
            Map.entry("needHsmtClarificationAttachments", hints.needHsmtClarificationAttachments()),
            Map.entry("needPetitionAttachments", hints.needPetitionAttachments()),
            Map.entry("needBbmtPdf", hints.needBbmtPdf()),
            Map.entry("needKqlcntDecisionPdf", hints.needKqlcntDecisionPdf()),
            Map.entry("needKqlcntEvaluationReport", hints.needKqlcntEvaluationReport()),
            Map.entry("decisionFileId", safe(hints.decisionFileId())),
            Map.entry("decisionFileName", safe(hints.decisionFileName())),
            Map.entry("reportFileId", safe(hints.reportFileId())),
            Map.entry("reportFileName", safe(hints.reportFileName())),
            Map.entry("goodFileId", safe(hints.goodFileId())),
            Map.entry("goodFileName", safe(hints.goodFileName()))
        ));
    }

    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof JsonNode node) {
            return normalizeJson(node);
        }
        return value;
    }

    private static Object normalizeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            TreeMap<String, Object> map = new TreeMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), normalizeJson(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            return MAPPER.convertValue(node, List.class);
        }
        if (node.isTextual()) {
            return node.asText().trim();
        }
        return node;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute SHA-256 hash", ex);
        }
    }
}
