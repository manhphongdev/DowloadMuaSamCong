package vn.muasamcong.downloader.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.muasamcong.downloader.util.Utils;

public final class AutoRunConfigStore {

    private static final Path CONFIG_FILE = Utils.dataFile("auto_run_config.json");
    private static final Object LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 2;

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
                    text(root, "startTime"),
                    text(root, "stopTime"),
                    text(root, "days")
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
                    "startTime", normalized.startTime() == null ? "" : normalized.startTime(),
                    "stopTime", normalized.stopTime() == null ? "" : normalized.stopTime(),
                    "days", normalized.days() == null ? "" : normalized.days()
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

    public record AutoRunConfig(boolean enabled, int intervalMinutes, String startTime, String stopTime, String days) {
        public static AutoRunConfig defaultConfig() {
            return new AutoRunConfig(false, 30, "08:00", "18:00", "MON,TUE,WED,THU,FRI,SAT,SUN");
        }

        public AutoRunConfig normalized() {
            int safeInterval = Math.max(1, Math.min(intervalMinutes, 1440));
            String safeStart = normalizeTimeOrDefault(startTime, "08:00");
            String safeStop = normalizeTimeOrDefault(stopTime, "18:00");
            String safeDays = normalizeDaysOrDefault(days, "MON,TUE,WED,THU,FRI,SAT,SUN");
            return new AutoRunConfig(enabled, safeInterval, safeStart, safeStop, safeDays);
        }

        private static String normalizeTimeOrDefault(String value, String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            String raw = value.trim();
            if (raw.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
                return raw;
            }
            return fallback;
        }

        private static String normalizeDaysOrDefault(String rawDays, String fallback) {
            if (rawDays == null || rawDays.isBlank()) {
                return fallback;
            }
            String[] parts = rawDays.split(",");
            Set<String> allowed = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
            LinkedHashSet<String> dedup = new LinkedHashSet<>();
            for (String part : parts) {
                if (part == null) {
                    continue;
                }
                String token = part.trim().toUpperCase();
                if (allowed.contains(token)) {
                    dedup.add(token);
                }
            }
            if (dedup.isEmpty()) {
                return fallback;
            }
            List<String> ordered = new ArrayList<>(dedup);
            return String.join(",", ordered);
        }
    }
}
