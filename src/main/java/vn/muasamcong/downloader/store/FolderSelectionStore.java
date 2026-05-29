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
import java.util.List;
import java.util.Map;
import vn.muasamcong.downloader.util.Utils;

public final class FolderSelectionStore {

    private static final Object LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 2;
    private static final Path FILE = Utils.dataFile("folder_selection.json");

    private FolderSelectionStore() {
    }

    public record FolderEntry(String path, boolean selected) {
    }

    public static List<String> loadFolders() {
        List<FolderEntry> entries = loadFolderEntries();
        List<String> result = new ArrayList<>();
        for (FolderEntry entry : entries) {
            result.add(entry.path());
        }
        return result;
    }

    public static List<FolderEntry> loadFolderEntries() {
        synchronized (LOCK) {
            if (!Files.exists(FILE)) {
                return List.of();
            }

            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(FILE));
                int schemaVersion = root != null && root.has("schemaVersion")
                    ? root.get("schemaVersion").asInt(1)
                    : 1;

                if (schemaVersion == 1) {
                    return loadV1Format(root);
                }

                JsonNode foldersNode = root == null ? null : root.get("folders");
                if (foldersNode == null || !foldersNode.isArray()) {
                    return List.of();
                }

                Map<String, Boolean> dedup = new LinkedHashMap<>();
                for (JsonNode item : foldersNode) {
                    if (item != null && item.isObject()) {
                        JsonNode pathNode = item.get("path");
                        JsonNode selectedNode = item.get("selected");
                        if (pathNode != null && pathNode.isTextual()) {
                            String path = pathNode.asText().trim();
                            boolean selected = selectedNode != null && selectedNode.isBoolean()
                                ? selectedNode.asBoolean()
                                : true;
                            if (!path.isEmpty()) {
                                dedup.put(path, selected);
                            }
                        }
                    }
                }

                List<FolderEntry> result = new ArrayList<>();
                for (Map.Entry<String, Boolean> entry : dedup.entrySet()) {
                    result.add(new FolderEntry(entry.getKey(), entry.getValue()));
                }
                return result;
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read folder selection file: " + FILE.toAbsolutePath(), ex);
            }
        }
    }

    private static List<FolderEntry> loadV1Format(JsonNode root) {
        JsonNode foldersNode = root == null ? null : root.get("folders");
        if (foldersNode == null || !foldersNode.isArray()) {
            return List.of();
        }

        Map<String, Boolean> dedup = new LinkedHashMap<>();
        for (JsonNode item : foldersNode) {
            if (item != null && item.isTextual()) {
                String value = item.asText().trim();
                if (!value.isEmpty()) {
                    dedup.put(value, true);
                }
            }
        }

        List<FolderEntry> result = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : dedup.entrySet()) {
            result.add(new FolderEntry(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public static void saveFolders(List<String> folders) {
        if (folders == null) {
            throw new IllegalArgumentException("folders is required");
        }
        List<FolderEntry> entries = new ArrayList<>();
        for (String folder : folders) {
            if (folder != null && !folder.isBlank()) {
                entries.add(new FolderEntry(folder.trim(), true));
            }
        }
        saveFolderEntries(entries);
    }

    public static void saveFolderEntries(List<FolderEntry> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries is required");
        }

        synchronized (LOCK) {
            Utils.ensureDirectory(FILE.toAbsolutePath().getParent());
            Map<String, Boolean> dedup = new LinkedHashMap<>();
            for (FolderEntry entry : entries) {
                if (entry != null && entry.path() != null && !entry.path().isBlank()) {
                    dedup.put(entry.path().trim(), entry.selected());
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
            sb.append("  \"updatedAt\": \"").append(LocalDateTime.now().format(TS)).append("\",\n");
            sb.append("  \"folders\": [\n");

            int index = 0;
            for (Map.Entry<String, Boolean> entry : dedup.entrySet()) {
                sb.append("    {\n");
                sb.append("      \"path\": \"").append(escape(entry.getKey())).append("\",\n");
                sb.append("      \"selected\": ").append(entry.getValue()).append("\n");
                sb.append("    }");
                if (index < dedup.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
                index++;
            }

            sb.append("  ]\n");
            sb.append("}\n");

            try {
                Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to write folder selection file: " + FILE.toAbsolutePath(), ex);
            }
        }
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
}
