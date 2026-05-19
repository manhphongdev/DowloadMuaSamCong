package vn.muasamcong.downloader.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import vn.muasamcong.downloader.util.Utils;

public final class BrowserProfileConfigStore {

    private static final Path CONFIG_FILE = Utils.dataFile("browser_profile_config.json");
    private static final Object LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private BrowserProfileConfigStore() {
    }

    public static BrowserProfileConfig load() {
        synchronized (LOCK) {
            if (!Files.exists(CONFIG_FILE)) {
                return BrowserProfileConfig.defaultConfig();
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(CONFIG_FILE));
                if (root == null || !root.isObject()) {
                    return BrowserProfileConfig.defaultConfig();
                }
                return new BrowserProfileConfig(text(root, "mode")).normalized();
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read browser profile config: " + CONFIG_FILE.toAbsolutePath(), ex);
            }
        }
    }

    public static void save(BrowserProfileConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        synchronized (LOCK) {
            Utils.ensureDirectory(CONFIG_FILE.toAbsolutePath().getParent());
            BrowserProfileConfig normalized = config.normalized();
            try {
                String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "schemaVersion", SCHEMA_VERSION,
                    "mode", normalized.mode()
                ));
                Files.writeString(CONFIG_FILE, json + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to save browser profile config: " + CONFIG_FILE.toAbsolutePath(), ex);
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

    public record BrowserProfileConfig(String mode) {
        public static BrowserProfileConfig defaultConfig() {
            return new BrowserProfileConfig("VISIBLE");
        }

        public BrowserProfileConfig normalized() {
            String normalizedMode = mode == null ? "VISIBLE" : mode.trim().toUpperCase();
            if (!"HIDDEN".equals(normalizedMode)) {
                normalizedMode = "VISIBLE";
            }
            return new BrowserProfileConfig(normalizedMode);
        }
    }
}
