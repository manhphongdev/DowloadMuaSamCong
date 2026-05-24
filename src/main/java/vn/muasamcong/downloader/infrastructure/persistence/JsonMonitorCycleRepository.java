package vn.muasamcong.downloader.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleLog;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleRepository;
import vn.muasamcong.downloader.util.Utils;

public final class JsonMonitorCycleRepository implements MonitorCycleRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_LOGS = 50;
    private static final Path FILE = Utils.dataFile("monitor_cycle_logs.json");
    private final Object lock = new Object();

    @Override
    public void append(MonitorCycleLog log) {
        if (log == null) {
            return;
        }
        synchronized (lock) {
            List<MonitorCycleLog> logs = new ArrayList<>(loadInternal());
            logs.add(log);
            if (logs.size() > MAX_LOGS) {
                logs = new ArrayList<>(logs.subList(logs.size() - MAX_LOGS, logs.size()));
            }
            saveInternal(logs);
        }
    }

    @Override
    public Optional<MonitorCycleLog> latest() {
        synchronized (lock) {
            List<MonitorCycleLog> logs = loadInternal();
            if (logs.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(logs.get(logs.size() - 1));
        }
    }

    private List<MonitorCycleLog> loadInternal() {
        if (!Files.exists(FILE)) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(FILE));
            JsonNode logsNode = root == null ? null : root.get("logs");
            if (logsNode == null || !logsNode.isArray()) {
                return List.of();
            }
            List<MonitorCycleLog> logs = new ArrayList<>();
            for (JsonNode node : logsNode) {
                MonitorCycleLog log = MAPPER.treeToValue(node, MonitorCycleLog.class);
                if (log != null) {
                    logs.add(log);
                }
            }
            return logs;
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read monitor cycle logs: " + FILE.toAbsolutePath(), ex);
        }
    }

    private void saveInternal(List<MonitorCycleLog> logs) {
        Utils.ensureDirectory(FILE.toAbsolutePath().getParent());
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "updatedAt", LocalDateTime.now().format(TS),
                "logs", logs
            ));
            Files.writeString(FILE, json + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write monitor cycle logs: " + FILE.toAbsolutePath(), ex);
        }
    }
}
