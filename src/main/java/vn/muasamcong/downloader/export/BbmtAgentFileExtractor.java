package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BbmtAgentFileExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BbmtAgentFileExtractor() {
    }

    public static List<AgentFileRef> extract(JsonNode lotOpen, JsonNode bidOpen, String notifyNo) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AgentFileRef> refs = new ArrayList<>();
        collectNode(refs, seen, bidOpen, notifyNo);
        if (lotOpen != null && lotOpen.isArray()) {
            for (JsonNode item : lotOpen) {
                if (item == null || item.isNull()) {
                    continue;
                }
                JsonNode bidOpenView = item.get("bidOpenView");
                if (bidOpenView != null && !bidOpenView.isNull()) {
                    if (bidOpenView.isTextual()) {
                        collectParsedJson(refs, seen, bidOpenView.asText(), notifyNo);
                    } else {
                        collectNode(refs, seen, bidOpenView, notifyNo);
                    }
                }
            }
        } else {
            collectNode(refs, seen, lotOpen, notifyNo);
        }
        return refs.stream().filter(ref -> ref != null && !isBlank(ref.fileId())).toList();
    }

    /** @deprecated use {@link #extract(JsonNode, JsonNode, String)} */
    public static List<AgentFileRef> fromLotOpen(JsonNode lotOpen) {
        return extract(lotOpen, null, null);
    }

    private static void collectParsedJson(
        List<AgentFileRef> refs,
        Set<String> seen,
        String raw,
        String notifyNo
    ) {
        if (isBlank(raw)) {
            return;
        }
        try {
            collectNode(refs, seen, MAPPER.readTree(raw), notifyNo);
        } catch (Exception ignored) {
        }
    }

    private static void collectNode(List<AgentFileRef> refs, Set<String> seen, JsonNode node, String notifyNo) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            addKnownFields(refs, seen, node, notifyNo);
            node.fields().forEachRemaining(entry -> collectNode(refs, seen, entry.getValue(), notifyNo));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectNode(refs, seen, child, notifyNo);
            }
        }
    }

    private static void addKnownFields(
        List<AgentFileRef> refs,
        Set<String> seen,
        JsonNode node,
        String notifyNo
    ) {
        addField(refs, seen, node, "fileId", "fileName", notifyNo);
        addField(refs, seen, node, "bbmtFileId", "bbmtFileName", notifyNo);
        addField(refs, seen, node, "minutesFileId", "minutesFileName", notifyNo);
        addField(refs, seen, node, "decisionFileId", "decisionFileName", notifyNo);
        addField(refs, seen, node, "attachFileId", "attachFileName", notifyNo);
    }

    private static void addField(
        List<AgentFileRef> refs,
        Set<String> seen,
        JsonNode node,
        String idField,
        String nameField,
        String notifyNo
    ) {
        String fileId = text(node, idField);
        if (!isUuidLike(fileId) || seen.contains(fileId)) {
            return;
        }
        String fileName = text(node, nameField);
        if (!BbmtFileSupport.looksLikeBbmtFileLabel(fileName) && !isBbmtContainer(node)) {
            return;
        }
        seen.add(fileId);
        refs.add(new AgentFileRef(fileId, resolveName(fileName, notifyNo)));
    }

    private static boolean isBbmtContainer(JsonNode node) {
        if (node == null) {
            return false;
        }
        String name = node.toString().toLowerCase();
        return name.contains("bidopeningminutes")
            || name.contains("bbmt")
            || name.contains("bidopenview")
            || name.contains("bidround");
    }

    private static String resolveName(String fileName, String notifyNo) {
        if (!isBlank(fileName) && BbmtFileSupport.looksLikeBbmtFileLabel(fileName)) {
            return fileName;
        }
        if (!isBlank(fileName)) {
            return fileName;
        }
        return BbmtFileSupport.defaultFileName(notifyNo);
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

    private static boolean isUuidLike(String value) {
        return value != null
            && value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "undefined".equalsIgnoreCase(value.trim());
    }
}
