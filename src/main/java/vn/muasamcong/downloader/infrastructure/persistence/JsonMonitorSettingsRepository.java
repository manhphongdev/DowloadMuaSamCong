package vn.muasamcong.downloader.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import vn.muasamcong.downloader.core.AppFeatures;
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
                int seleniumConcurrency = root.path("seleniumDownloadConcurrency").asInt(-1);
                if (seleniumConcurrency < 0) {
                    seleniumConcurrency = root.path("downloadConcurrency").asInt(
                        AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT
                    );
                }
                return new MonitorSettings(
                    root.path("enabled").asBoolean(false),
                    root.path("intervalMinutes").asInt(30),
                    root.path("downloadFilesAfterSheet").asBoolean(false),
                    root.path("agentDownloadConcurrency").asInt(AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_DEFAULT),
                    root.path("bbmtSeleniumDownload").asBoolean(true),
                    seleniumConcurrency
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
                    "downloadFilesAfterSheet", normalized.downloadFilesAfterSheet(),
                    "agentDownloadConcurrency", normalized.agentDownloadConcurrency(),
                    "bbmtSeleniumDownload", normalized.bbmtSeleniumDownload(),
                    "seleniumDownloadConcurrency", normalized.seleniumDownloadConcurrency()
                ));
                writeAtomically(FILE, json + System.lineSeparator());
            } catch (IOException ex) {
                throw new RuntimeException("Unable to write monitor settings: " + FILE.toAbsolutePath(), ex);
            }
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
