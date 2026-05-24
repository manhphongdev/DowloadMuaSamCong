package vn.muasamcong.downloader.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import vn.muasamcong.downloader.domain.monitor.MonitorSettings;
import vn.muasamcong.downloader.domain.monitor.MonitorSettingsRepository;
import vn.muasamcong.downloader.util.Utils;

public final class JsonMonitorSettingsRepository implements MonitorSettingsRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;
    private static final Path FILE = Utils.dataFile("monitor_settings.json");
    private final Object lock = new Object();

    @Override
    public MonitorSettings load() {
        synchronized (lock) {
            if (!Files.exists(FILE)) {
                return MonitorSettings.defaults();
            }
            try {
                JsonNode root = MAPPER.readTree(Files.newBufferedReader(FILE));
                if (root == null || !root.isObject()) {
                    return MonitorSettings.defaults();
                }
                return new MonitorSettings(
                    root.path("enabled").asBoolean(false),
                    root.path("intervalMinutes").asInt(30),
                    root.path("downloadFilesAfterSheet").asBoolean(false)
                ).normalized();
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read monitor settings: " + FILE.toAbsolutePath(), ex);
            }
        }
    }

    @Override
    public void save(MonitorSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings is required");
        }
        MonitorSettings normalized = settings.normalized();
        synchronized (lock) {
            Utils.ensureDirectory(FILE.toAbsolutePath().getParent());
            try {
                String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                    "schemaVersion", SCHEMA_VERSION,
                    "enabled", normalized.enabled(),
                    "intervalMinutes", normalized.intervalMinutes(),
                    "downloadFilesAfterSheet", normalized.downloadFilesAfterSheet()
                ));
                Files.writeString(FILE, json + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to write monitor settings: " + FILE.toAbsolutePath(), ex);
            }
        }
    }
}
