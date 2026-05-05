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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.muasamcong.downloader.util.Utils;

public final class FolderSelectionStore {

    private static final Object LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 1;
    private static final Path FILE = Utils.dataFile("folder_selection.json");

    private FolderSelectionStore() {
    }

    public static List<String> loadFolders() {
        synchronized (LOCK) {
            if (!Files.exists(FILE)) {
                return List.of();
            }

            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(FILE));
                JsonNode foldersNode = root == null ? null : root.get("folders");
                if (foldersNode == null || !foldersNode.isArray()) {
                    return List.of();
                }

                Set<String> dedup = new LinkedHashSet<>();
                for (JsonNode item : foldersNode) {
                    if (item != null && item.isTextual()) {
                        String value = item.asText().trim();
                        if (!value.isEmpty()) {
                            dedup.add(value);
                        }
                    }
                }
                return new ArrayList<>(dedup);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read folder selection file: " + FILE.toAbsolutePath(), ex);
            }
        }
    }

    public static void saveFolders(List<String> folders) {
        if (folders == null) {
            throw new IllegalArgumentException("folders is required");
        }

        synchronized (LOCK) {
            Utils.ensureDirectory(FILE.toAbsolutePath().getParent());
            LinkedHashSet<String> dedup = new LinkedHashSet<>();
            for (String folder : folders) {
                if (folder != null && !folder.isBlank()) {
                    dedup.add(folder.trim());
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
            sb.append("  \"updatedAt\": \"").append(LocalDateTime.now().format(TS)).append("\",\n");
            sb.append("  \"folders\": [\n");

            int index = 0;
            for (String folder : dedup) {
                sb.append("    \"").append(escape(folder)).append("\"");
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
