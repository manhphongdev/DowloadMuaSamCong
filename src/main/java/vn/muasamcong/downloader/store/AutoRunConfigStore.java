package vn.muasamcong.downloader.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import vn.muasamcong.downloader.util.Utils;

public final class AutoRunConfigStore {

    private static final Path CONFIG_FILE = Utils.dataFile("auto_run_config.json");
    private static final Object LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private AutoRunConfigStore() {
    }

    public static AutoRunConfig load() {
        synchronized (LOCK) {
            if (!Files.exists(CONFIG_FILE)) {
                return AutoRunConfig.defaultConfig();
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(CONFIG_FILE));
                if (root == null || !root.isObject()) {
                    return AutoRunConfig.defaultConfig();
                }
                return new AutoRunConfig(
                    root.path("enabled").asBoolean(false),
                    root.path("intervalMinutes").asInt(30),
                    text(root, "mode"),
                    text(root, "specificTime")
                ).normalized();
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read auto run config: " + CONFIG_FILE.toAbsolutePath(), ex);
            }
        }
    }

    public static void save(AutoRunConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        synchronized (LOCK) {
            Utils.ensureDirectory(CONFIG_FILE.toAbsolutePath().getParent());
            AutoRunConfig normalized = config.normalized();
            try {
                String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                    "schemaVersion", SCHEMA_VERSION,
                    "enabled", normalized.enabled(),
                    "intervalMinutes", normalized.intervalMinutes(),
                    "mode", normalized.mode(),
                    "specificTime", normalized.specificTime() == null ? "" : normalized.specificTime()
                ));
                Files.writeString(CONFIG_FILE, json + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to save auto run config: " + CONFIG_FILE.toAbsolutePath(), ex);
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

    public record AutoRunConfig(boolean enabled, int intervalMinutes, String mode, String specificTime) {
        public static AutoRunConfig defaultConfig() {
            return new AutoRunConfig(false, 30, "INTERVAL", null);
        }

        public AutoRunConfig normalized() {
            int clamped = Math.max(30, Math.min(intervalMinutes, 1440));
            int remainder = clamped % 30;
            int safeInterval = remainder == 0 ? clamped : Math.min(clamped + (30 - remainder), 1440);
            String normalizedMode = (mode == null || mode.isBlank()) ? "INTERVAL" : mode.trim().toUpperCase();
            if (!"SPECIFIC_TIME".equals(normalizedMode)) {
                normalizedMode = "INTERVAL";
            }

            String normalizedTime = null;
            if (specificTime != null && !specificTime.isBlank()) {
                String raw = specificTime.trim();
                if (raw.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
                    normalizedTime = raw;
                }
            }

            return new AutoRunConfig(enabled, safeInterval, normalizedMode, normalizedTime);
        }
    }
}
