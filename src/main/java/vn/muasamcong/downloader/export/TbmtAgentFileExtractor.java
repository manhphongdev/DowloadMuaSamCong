package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class TbmtAgentFileExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern UUID_LIKE = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    public static final Path CLARIFICATION_SUBDIR = Path.of("lam-ro-hsmt");
    public static final Path PETITION_SUBDIR = Path.of("kien-nghi");

    private TbmtAgentFileExtractor() {
    }

    public static List<AgentFileRef> clarificationRefs(JsonNode clarification) {
        if (clarification == null || clarification.isMissingNode() || clarification.isNull()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AgentFileRef> refs = new ArrayList<>();
        JsonNode versions = clarification.get("biduClarifyReqInvAndContentViewVersionDTOList");
        if (versions != null && versions.isArray()) {
            for (JsonNode version : versions) {
                JsonNode items = version.get("biduClarifyReqInvAndContentViewList");
                if (items == null || !items.isArray()) {
                    continue;
                }
                for (JsonNode item : items) {
                    addClarificationItem(refs, seen, item);
                }
            }
        }
        return refs;
    }

    public static List<AgentFileRef> petitionRefs(JsonNode petition) {
        if (petition == null || petition.isMissingNode() || petition.isNull()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AgentFileRef> refs = new ArrayList<>();
        JsonNode versions = petition.get("biduPetitionContractorVersionDTOList");
        if (versions != null && versions.isArray()) {
            for (JsonNode version : versions) {
                JsonNode items = version.get("biduPetitionContractorDTOList");
                if (items == null || !items.isArray()) {
                    continue;
                }
                for (JsonNode item : items) {
                    addPetitionItem(refs, seen, item);
                }
            }
        }
        return refs;
    }

    private static void addClarificationItem(List<AgentFileRef> target, Set<String> seen, JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return;
        }
        Path subDir = CLARIFICATION_SUBDIR;
        addFieldPair(target, seen, item, "clarify_file_id", "clarify_file_name", subDir);
        addFieldPair(target, seen, item, "clarify_file_id_en", "clarify_file_name_en", subDir);
        addFieldPair(target, seen, item, "clarifyResFileId", "clarifyResFileName", subDir);
        addFieldPair(target, seen, item, "clarifyResFileIdEn", "clarifyResFileNameEn", subDir);
        addFieldPair(target, seen, item, "fileId", "fileName", subDir);
        addFieldPair(target, seen, item, "attachFileId", "attachFileName", subDir);
        addJsonArrayFilePairs(target, seen, item, "replyAdditional", "additionalFileId", "additionalFileName", subDir);
    }

    private static void addPetitionItem(List<AgentFileRef> target, Set<String> seen, JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return;
        }
        Path subDir = PETITION_SUBDIR;
        addFieldPair(target, seen, item, "reqFileId", "reqFileName", subDir);
        addFieldPair(target, seen, item, "resFileId", "resFileName", subDir);
        addFieldPair(target, seen, item, "fileId", "fileName", subDir);
        addFieldPair(target, seen, item, "attachFileId", "attachFileName", subDir);
        addJsonArrayFilePairs(target, seen, item, "content", "reqFileId", "reqFileName", subDir);
        addJsonArrayFilePairs(target, seen, item, "content", "resFileId", "resFileName", subDir);
    }

    private static void addJsonArrayFilePairs(
        List<AgentFileRef> target,
        Set<String> seen,
        JsonNode item,
        String jsonField,
        String idField,
        String nameField,
        Path subDir
    ) {
        String raw = text(item, jsonField);
        if (isBlank(raw) || !raw.startsWith("[")) {
            return;
        }
        try {
            JsonNode array = MAPPER.readTree(raw);
            if (array == null || !array.isArray()) {
                return;
            }
            for (JsonNode entry : array) {
                addFieldPair(target, seen, entry, idField, nameField, subDir);
            }
        } catch (Exception ignored) {
        }
    }

    private static void addFieldPair(
        List<AgentFileRef> target,
        Set<String> seen,
        JsonNode item,
        String idField,
        String nameField,
        Path subDir
    ) {
        String fileId = text(item, idField);
        if (!isUuidLike(fileId) || seen.contains(fileId)) {
            return;
        }
        seen.add(fileId);
        target.add(new AgentFileRef(fileId, text(item, nameField), subDir));
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
        return value != null && UUID_LIKE.matcher(value.trim()).matches();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "undefined".equalsIgnoreCase(value.trim());
    }
}
