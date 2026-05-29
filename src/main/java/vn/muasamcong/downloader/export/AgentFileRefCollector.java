package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class AgentFileRefCollector {

    private static final Pattern UUID_LIKE = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private AgentFileRefCollector() {
    }

    static List<AgentFileRef> collectFromJson(JsonNode root, Path subDir) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AgentFileRef> refs = new ArrayList<>();
        walk(root, subDir, seen, refs);
        return refs;
    }

    private static void walk(JsonNode node, Path subDir, Set<String> seen, List<AgentFileRef> refs) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            String fileId = firstNonBlank(
                text(node, "fileId"),
                text(node, "attachFileId"),
                text(node, "decisionFileId"),
                text(node, "otherFileId"),
                text(node, "briefFileId"),
                text(node, "cancelFileAttachId"),
                text(node, "appraisalFileId")
            );
            if (isUuidLike(fileId)) {
                String fileName = firstNonBlank(
                    text(node, "fileName"),
                    text(node, "attachFileName"),
                    text(node, "decisionFileName"),
                    text(node, "otherFileName"),
                    text(node, "briefFileName"),
                    text(node, "cancelFileAttachName"),
                    text(node, "appraisalFileName")
                );
                if (!seen.contains(fileId)) {
                    seen.add(fileId);
                    refs.add(new AgentFileRef(fileId, fileName, subDir));
                }
            }
            node.fields().forEachRemaining(entry ->
                walk(entry.getValue(), subDir, seen, refs)
            );
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                walk(child, subDir, seen, refs);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText(null);
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text.trim())) {
            return null;
        }
        return text.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isUuidLike(String value) {
        return value != null && UUID_LIKE.matcher(value.trim()).matches();
    }
}
