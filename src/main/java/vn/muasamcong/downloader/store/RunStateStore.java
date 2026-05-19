package vn.muasamcong.downloader.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.util.Utils;

public final class RunStateStore {

    private static final Object LOCK = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 3;
    private static final Path STATE_FILE = Utils.dataFile("run_state.json");

    private RunStateStore() {
    }

    public static List<KeywordTarget> filterPending(List<KeywordTarget> targets) {
        StateData state = loadStateData();
        List<KeywordTarget> pending = new ArrayList<>();
        for (KeywordTarget target : targets) {
            StateRecord record = state.recordsByKey().get(buildTargetKey(target));
            boolean completed = record != null && "SUCCESS".equals(record.status());
            if (!completed) {
                pending.add(target);
            }
        }
        return pending;
    }

    public static void markSuccess(KeywordTarget target, int attempts, int downloadedFilesCount) {
        upsert(target, "SUCCESS", attempts, null, downloadedFilesCount);
    }

    public static void markFailure(KeywordTarget target, int attempts, String error, int downloadedFilesCount) {
        upsert(target, "FAILED", attempts, error, downloadedFilesCount);
    }

    public static void markStopped(KeywordTarget target, int attempts, String error, int downloadedFilesCount) {
        upsert(target, "STOPPED", attempts, error, downloadedFilesCount);
    }

    public static void clear() {
        synchronized (LOCK) {
            try {
                Files.deleteIfExists(STATE_FILE);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to clear run state: " + STATE_FILE.toAbsolutePath(), ex);
            }
        }
    }

    public static int removeByKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        synchronized (LOCK) {
            StateData data = loadStateDataInternal();
            LinkedHashMap<String, StateRecord> records = new LinkedHashMap<>(data.recordsByKey());
            int before = records.size();
            records.keySet().removeIf(keys::contains);
            int removed = before - records.size();
            if (removed <= 0) {
                return 0;
            }

            if (records.isEmpty()) {
                try {
                    Files.deleteIfExists(STATE_FILE);
                } catch (IOException ex) {
                    throw new RuntimeException("Unable to update run state: " + STATE_FILE.toAbsolutePath(), ex);
                }
            } else {
                saveStateDataInternal(new StateData(records));
            }
            return removed;
        }
    }

    private static void upsert(KeywordTarget target, String status, int attempts, String error, int downloadedFilesCount) {
        if (target == null || target.keyword() == null || target.keyword().isBlank()) {
            return;
        }
        synchronized (LOCK) {
            StateData data = loadStateDataInternal();
            LinkedHashMap<String, StateRecord> records = new LinkedHashMap<>(data.recordsByKey());
            String key = buildTargetKey(target);
            String now = LocalDateTime.now().format(TS);
            StateRecord existing = records.get(key);
            String firstSeenAt = existing == null ? now : existing.firstSeenAt();
            StateRecord next = new StateRecord(
                key,
                normalize(target.keyword()),
                normalize(target.folderPath() == null ? null : target.folderPath().toAbsolutePath().normalize().toString()),
                normalize(status),
                firstSeenAt,
                now,
                Math.max(0, attempts),
                normalize(error),
                Math.max(0, downloadedFilesCount)
            );
            records.put(key, next);
            saveStateDataInternal(new StateData(records));
        }
    }

    public static List<StateRecord> loadRecords() {
        synchronized (LOCK) {
            return List.copyOf(loadStateDataInternal().recordsByKey().values());
        }
    }

    private static StateData loadStateData() {
        synchronized (LOCK) {
            return loadStateDataInternal();
        }
    }

    private static StateData loadStateDataInternal() {
        LinkedHashMap<String, StateRecord> records = new LinkedHashMap<>();
        if (!Files.exists(STATE_FILE)) {
            return new StateData(records);
        }

        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(STATE_FILE));

            JsonNode recordsNode = root == null ? null : root.get("records");
            if (recordsNode != null && recordsNode.isArray()) {
                for (JsonNode node : recordsNode) {
                    StateRecord record = toRecord(node);
                    if (record != null && record.key() != null && !record.key().isBlank()) {
                        records.put(record.key(), record);
                    }
                }
            }

            return new StateData(records);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read run state: " + STATE_FILE.toAbsolutePath(), ex);
        }
    }

    private static StateRecord toRecord(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String key = normalize(text(node, "key"));
        String keyword = normalize(text(node, "keyword"));
        String folderPath = normalize(text(node, "folderPath"));
        String status = normalize(text(node, "status"));
        String firstSeenAt = normalize(text(node, "firstSeenAt"));
        String lastRunAt = normalize(text(node, "lastRunAt"));
        int attempts = number(node, "attempts");
        String error = normalize(text(node, "error"));
        int downloadedFilesCount = number(node, "downloadedFilesCount");
        if (key == null || keyword == null || folderPath == null) {
            return null;
        }
        return new StateRecord(
            key,
            keyword,
            folderPath,
            status == null ? "UNKNOWN" : status,
            firstSeenAt,
            lastRunAt,
            Math.max(0, attempts),
            error,
            Math.max(0, downloadedFilesCount)
        );
    }

    private static void saveStateDataInternal(StateData stateData) {
        Utils.ensureDirectory(STATE_FILE.toAbsolutePath().getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
        sb.append("  \"updatedAt\": \"").append(LocalDateTime.now().format(TS)).append("\",\n");
        sb.append("  \"records\": [\n");

        List<StateRecord> records = new ArrayList<>(stateData.recordsByKey().values());
        for (int i = 0; i < records.size(); i++) {
            StateRecord record = records.get(i);
            sb.append("    {\n");
            sb.append("      \"key\": \"").append(escape(record.key())).append("\",\n");
            sb.append("      \"keyword\": \"").append(escape(orEmpty(record.keyword()))).append("\",\n");
            sb.append("      \"folderPath\": \"").append(escape(orEmpty(record.folderPath()))).append("\",\n");
            sb.append("      \"status\": \"").append(escape(orEmpty(record.status()))).append("\",\n");
            sb.append("      \"firstSeenAt\": \"").append(escape(orEmpty(record.firstSeenAt()))).append("\",\n");
            sb.append("      \"lastRunAt\": \"").append(escape(orEmpty(record.lastRunAt()))).append("\",\n");
            sb.append("      \"attempts\": ").append(record.attempts()).append(",\n");
            sb.append("      \"error\": \"").append(escape(orEmpty(record.error()))).append("\",\n");
            sb.append("      \"downloadedFilesCount\": ").append(record.downloadedFilesCount()).append("\n");
            sb.append("    }");
            if (i < records.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }

        sb.append("  ]\n");
        sb.append("}\n");

        try {
            Files.writeString(STATE_FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write run state: " + STATE_FILE.toAbsolutePath(), ex);
        }
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asInt() : 0;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String buildTargetKey(KeywordTarget target) {
        String folder = target.folderPath() == null
            ? ""
            : target.folderPath().toAbsolutePath().normalize().toString();
        return folder + "|" + target.keyword().trim();
    }

    public record StateRecord(
        String key,
        String keyword,
        String folderPath,
        String status,
        String firstSeenAt,
        String lastRunAt,
        int attempts,
        String error,
        int downloadedFilesCount
    ) {
    }

    private record StateData(
        Map<String, StateRecord> recordsByKey
    ) {
    }
}
