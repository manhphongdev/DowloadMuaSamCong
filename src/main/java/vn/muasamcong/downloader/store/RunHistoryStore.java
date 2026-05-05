package vn.muasamcong.downloader.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.util.Utils;

public final class RunHistoryStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 1;
    private static final Path HISTORY_DIR = Utils.dataDirectory().resolve("run_history");

    private RunHistoryStore() {
    }

    public static void appendRun(
        List<Path> rootFolders,
        RunStats stats,
        boolean stopped,
        LocalDateTime startedAt,
        LocalDateTime endedAt
    ) {
        if (stats == null) {
            return;
        }

        Utils.ensureDirectory(HISTORY_DIR);
        String timestamp = (endedAt == null ? LocalDateTime.now() : endedAt).format(FILE_TS);
        Path file = HISTORY_DIR.resolve("run_" + timestamp + ".json");

        List<String> folders = new ArrayList<>();
        if (rootFolders != null) {
            for (Path folder : rootFolders) {
                if (folder != null) {
                    folders.add(folder.toAbsolutePath().normalize().toString());
                }
            }
        }

        RunHistoryPayload payload = new RunHistoryPayload(
            SCHEMA_VERSION,
            startedAt == null ? null : startedAt.format(TS),
            endedAt == null ? null : endedAt.format(TS),
            stopped ? "STOPPED" : "COMPLETED",
            stats.getTotalKeywords(),
            stats.getSuccessCount(),
            stats.getFailCount(),
            folders,
            stats.getProcessedRecords()
        );

        try {
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(file, content + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write run history: " + file.toAbsolutePath(), ex);
        }
    }

    public static void clear() {
        Utils.deleteDirectoryQuietly(HISTORY_DIR);
    }

    private record RunHistoryPayload(
        int schemaVersion,
        String startedAt,
        String endedAt,
        String status,
        int totalKeywords,
        int successCount,
        int failCount,
        List<String> rootFolders,
        List<RunStats.ProcessedRecord> processedRecords
    ) {
    }
}
