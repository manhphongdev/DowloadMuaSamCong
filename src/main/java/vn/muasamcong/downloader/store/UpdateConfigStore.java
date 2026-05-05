package vn.muasamcong.downloader.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import vn.muasamcong.downloader.util.Utils;

public final class UpdateConfigStore {

    private static final Object LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;
    private static final Path CONFIG_FILE = Utils.dataFile("update_config.json");

    private UpdateConfigStore() {
    }

    public static UpdateConfig load() {
        synchronized (LOCK) {
            if (!Files.exists(CONFIG_FILE)) {
                return UpdateConfig.defaultConfig();
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(CONFIG_FILE));
                if (root == null || !root.isObject()) {
                    return UpdateConfig.defaultConfig();
                }
                return new UpdateConfig(
                    text(root, "metadataUrl"),
                    root.path("autoCheckOnStartup").asBoolean(false)
                ).normalized();
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read update config: " + CONFIG_FILE.toAbsolutePath(), ex);
            }
        }
    }

    public static void save(UpdateConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        synchronized (LOCK) {
            Utils.ensureDirectory(CONFIG_FILE.toAbsolutePath().getParent());
            UpdateConfig normalized = config.normalized();
            try {
                String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                    "schemaVersion", SCHEMA_VERSION,
                    "metadataUrl", normalized.metadataUrl() == null ? "" : normalized.metadataUrl(),
                    "autoCheckOnStartup", normalized.autoCheckOnStartup()
                ));
                Files.writeString(CONFIG_FILE, json + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to save update config: " + CONFIG_FILE.toAbsolutePath(), ex);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    public record UpdateConfig(String metadataUrl, boolean autoCheckOnStartup) {
        public static UpdateConfig defaultConfig() {
            return new UpdateConfig(null, false);
        }

        public UpdateConfig normalized() {
            return new UpdateConfig(
                metadataUrl == null || metadataUrl.isBlank() ? null : metadataUrl.trim(),
                autoCheckOnStartup
            );
        }
    }
}
