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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.model.MonitorDownloadStatus;
import vn.muasamcong.downloader.model.MonitorKeywordRecord;
import vn.muasamcong.downloader.model.MonitorStatus;
import vn.muasamcong.downloader.util.Utils;

public final class MonitorKeywordStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 1;
    private static final Path STORAGE_FILE = Utils.dataFile("monitor_tracking_keywords.json");
    private static final Object LOCK = new Object();

    private MonitorKeywordStore() {
    }

    public static SyncResult syncFromFolderKeywords(List<String> folderKeywordKeys) {
        synchronized (LOCK) {
            LinkedHashMap<String, MonitorKeywordRecord> records = loadByKeyInternal();
            String now = now();
            int added = 0;
            int touched = 0;
            List<String> addedKeys = new ArrayList<>();

            for (String folderKeywordKey : folderKeywordKeys) {
                ParsedKey parsed = parseKey(folderKeywordKey);
                if (parsed == null) {
                    continue;
                }
                MonitorKeywordRecord existing = records.get(parsed.key());
                if (existing == null) {
                    records.put(parsed.key(), new MonitorKeywordRecord(
                        UUID.randomUUID().toString(),
                        parsed.key(),
                        parsed.folderPath(),
                        parsed.keyword(),
                        normalizeKeyword(parsed.keyword()),
                        true,
                        now,
                        now,
                        now,
                        now,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        MonitorDownloadStatus.PENDING,
                        null,
                        null,
                        0,
                        0,
                        null,
                        0,
                        0,
                        0,
                        0
                    ));
                    added++;
                    touched++;
                    addedKeys.add(parsed.key());
                    continue;
                }

                records.put(parsed.key(), new MonitorKeywordRecord(
                    existing.id(),
                    existing.key(),
                    parsed.folderPath(),
                    parsed.keyword(),
                    normalizeKeyword(parsed.keyword()),
                    existing.enabled(),
                    coalesce(existing.firstSeenAt(), now),
                    now,
                    coalesce(existing.createdAt(), now),
                    now,
                    existing.lastMonitorAt(),
                    existing.lastStatus(),
                    existing.lastMessage(),
                    existing.lastMatchedNotifyNo(),
                    existing.lastMatchedDetailUrl(),
                    existing.apiParams(),
                    existing.downloadStatus() == null ? MonitorDownloadStatus.PENDING : existing.downloadStatus(),
                    existing.lastDownloadAt(),
                    existing.lastDownloadMessage(),
                    existing.lastDownloadedFiles(),
                    existing.downloadAttempts(),
                    existing.lastDownloadSuccessAt(),
                    existing.runCount(),
                    existing.successCount(),
                    existing.notFoundCount(),
                    existing.errorCount()
                ));
                touched++;
            }

            saveInternal(records, now);
            return new SyncResult(records.size(), added, touched, List.copyOf(addedKeys));
        }
    }

    public static List<MonitorKeywordRecord> loadEnabledRecords() {
        synchronized (LOCK) {
            List<MonitorKeywordRecord> records = new ArrayList<>(loadByKeyInternal().values());
            return records.stream().filter(MonitorKeywordRecord::enabled).toList();
        }
    }

    public static int removeByKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        synchronized (LOCK) {
            LinkedHashMap<String, MonitorKeywordRecord> records = loadByKeyInternal();
            int before = records.size();
            records.keySet().removeIf(keys::contains);
            int removed = before - records.size();
            if (removed > 0) {
                if (records.isEmpty()) {
                    clearInternal();
                } else {
                    saveInternal(records, now());
                }
            }
            return removed;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            clearInternal();
        }
    }

    public static void markMonitorResult(String key, MonitorStatus status, String message) {
        if (isBlank(key)) {
            return;
        }
        synchronized (LOCK) {
            LinkedHashMap<String, MonitorKeywordRecord> records = loadByKeyInternal();
            MonitorKeywordRecord existing = records.get(key);
            if (existing == null) {
                return;
            }
            String now = now();
            int runCount = existing.runCount() + 1;
            int successCount = existing.successCount();
            int notFoundCount = existing.notFoundCount();
            int errorCount = existing.errorCount();
            if (status == MonitorStatus.OK) {
                successCount++;
            } else if (status == MonitorStatus.NOT_FOUND) {
                notFoundCount++;
            } else if (status == MonitorStatus.ERROR) {
                errorCount++;
            }
            records.put(key, new MonitorKeywordRecord(
                existing.id(),
                existing.key(),
                existing.folderPath(),
                existing.keyword(),
                existing.normalizedKeyword(),
                existing.enabled(),
                existing.firstSeenAt(),
                existing.lastSeenAt(),
                existing.createdAt(),
                now,
                now,
                status,
                message,
                existing.lastMatchedNotifyNo(),
                existing.lastMatchedDetailUrl(),
                existing.apiParams(),
                existing.downloadStatus(),
                existing.lastDownloadAt(),
                existing.lastDownloadMessage(),
                existing.lastDownloadedFiles(),
                existing.downloadAttempts(),
                existing.lastDownloadSuccessAt(),
                runCount,
                successCount,
                notFoundCount,
                errorCount
            ));
            saveInternal(records, now);
        }
    }

    public static void markDownloadRunning(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<String> set = new HashSet<>(keys);
        synchronized (LOCK) {
            LinkedHashMap<String, MonitorKeywordRecord> records = loadByKeyInternal();
            String now = now();
            for (Map.Entry<String, MonitorKeywordRecord> entry : records.entrySet()) {
                if (!set.contains(entry.getKey())) {
                    continue;
                }
                MonitorKeywordRecord existing = entry.getValue();
                records.put(entry.getKey(), new MonitorKeywordRecord(
                    existing.id(), existing.key(), existing.folderPath(), existing.keyword(), existing.normalizedKeyword(),
                    existing.enabled(), existing.firstSeenAt(), existing.lastSeenAt(), existing.createdAt(), now,
                    existing.lastMonitorAt(), existing.lastStatus(), existing.lastMessage(), existing.lastMatchedNotifyNo(),
                    existing.lastMatchedDetailUrl(), existing.apiParams(), MonitorDownloadStatus.RUNNING, now,
                    "Downloading", existing.lastDownloadedFiles(), existing.downloadAttempts() + 1,
                    existing.lastDownloadSuccessAt(), existing.runCount(), existing.successCount(), existing.notFoundCount(),
                    existing.errorCount()
                ));
            }
            saveInternal(records, now);
        }
    }

    public static void applyDownloadRunStats(RunStats stats) {
        if (stats == null) {
            return;
        }
        synchronized (LOCK) {
            LinkedHashMap<String, MonitorKeywordRecord> records = loadByKeyInternal();
            String now = now();
            for (RunStats.ProcessedRecord processed : stats.getProcessedRecords()) {
                if (processed == null) {
                    continue;
                }
                String key = buildKey(processed.folderPath(), processed.keyword());
                MonitorKeywordRecord existing = records.get(key);
                if (existing == null) {
                    continue;
                }
                MonitorDownloadStatus status = mapDownloadStatus(processed.status(), processed.downloadedFilesCount());
                String message = processed.error();
                if (message == null || message.isBlank()) {
                    message = status == MonitorDownloadStatus.SUCCESS
                        ? "Downloaded"
                        : status == MonitorDownloadStatus.PARTIAL ? "Downloaded partially" : "Download failed";
                }
                String successAt = status == MonitorDownloadStatus.SUCCESS ? now : existing.lastDownloadSuccessAt();
                records.put(key, new MonitorKeywordRecord(
                    existing.id(), existing.key(), existing.folderPath(), existing.keyword(), existing.normalizedKeyword(),
                    existing.enabled(), existing.firstSeenAt(), existing.lastSeenAt(), existing.createdAt(), now,
                    existing.lastMonitorAt(), existing.lastStatus(), existing.lastMessage(), existing.lastMatchedNotifyNo(),
                    existing.lastMatchedDetailUrl(), existing.apiParams(), status, now, message,
                    Math.max(0, processed.downloadedFilesCount()), existing.downloadAttempts(), successAt,
                    existing.runCount(), existing.successCount(), existing.notFoundCount(), existing.errorCount()
                ));
            }
            saveInternal(records, now);
        }
    }

    public static int resetStuckRunningRecords() {
        synchronized (LOCK) {
            LinkedHashMap<String, MonitorKeywordRecord> records = loadByKeyInternal();
            String now = now();
            int count = 0;
            for (Map.Entry<String, MonitorKeywordRecord> entry : records.entrySet()) {
                MonitorKeywordRecord existing = entry.getValue();
                if (existing.downloadStatus() != MonitorDownloadStatus.RUNNING) {
                    continue;
                }
                records.put(entry.getKey(), new MonitorKeywordRecord(
                    existing.id(), existing.key(), existing.folderPath(), existing.keyword(), existing.normalizedKeyword(),
                    existing.enabled(), existing.firstSeenAt(), existing.lastSeenAt(), existing.createdAt(), now,
                    existing.lastMonitorAt(), existing.lastStatus(), existing.lastMessage(), existing.lastMatchedNotifyNo(),
                    existing.lastMatchedDetailUrl(), existing.apiParams(), MonitorDownloadStatus.FAILED, now,
                    "Interrupted", existing.lastDownloadedFiles(), existing.downloadAttempts(),
                    existing.lastDownloadSuccessAt(), existing.runCount(), existing.successCount(), existing.notFoundCount(),
                    existing.errorCount()
                ));
                count++;
            }
            if (count > 0) {
                saveInternal(records, now);
            }
            return count;
        }
    }

    public static void markDownloadFailed(List<String> keys, String message) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<String> set = new HashSet<>(keys);
        synchronized (LOCK) {
            LinkedHashMap<String, MonitorKeywordRecord> records = loadByKeyInternal();
            String now = now();
            for (Map.Entry<String, MonitorKeywordRecord> entry : records.entrySet()) {
                if (!set.contains(entry.getKey())) {
                    continue;
                }
                MonitorKeywordRecord existing = entry.getValue();
                records.put(entry.getKey(), new MonitorKeywordRecord(
                    existing.id(), existing.key(), existing.folderPath(), existing.keyword(), existing.normalizedKeyword(),
                    existing.enabled(), existing.firstSeenAt(), existing.lastSeenAt(), existing.createdAt(), now,
                    existing.lastMonitorAt(), existing.lastStatus(), existing.lastMessage(), existing.lastMatchedNotifyNo(),
                    existing.lastMatchedDetailUrl(), existing.apiParams(), MonitorDownloadStatus.FAILED, now,
                    isBlank(message) ? "Download failed" : message, existing.lastDownloadedFiles(), existing.downloadAttempts(),
                    existing.lastDownloadSuccessAt(), existing.runCount(), existing.successCount(), existing.notFoundCount(),
                    existing.errorCount()
                ));
            }
            saveInternal(records, now);
        }
    }

    private static MonitorDownloadStatus mapDownloadStatus(String status, int downloadedFiles) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if ("SUCCESS".equals(normalized)) {
            return MonitorDownloadStatus.SUCCESS;
        }
        if (downloadedFiles > 0) {
            return MonitorDownloadStatus.PARTIAL;
        }
        return MonitorDownloadStatus.FAILED;
    }

    private static String buildKey(String folderPath, String keyword) {
        if (isBlank(folderPath) || isBlank(keyword)) {
            return "";
        }
        return folderPath.trim() + "|" + keyword.trim();
    }

    private static LinkedHashMap<String, MonitorKeywordRecord> loadByKeyInternal() {
        LinkedHashMap<String, MonitorKeywordRecord> records = new LinkedHashMap<>();
        if (!Files.exists(STORAGE_FILE)) {
            return records;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(STORAGE_FILE));
            JsonNode recordsNode = root == null ? null : root.get("records");
            if (recordsNode == null || !recordsNode.isArray()) {
                return records;
            }
            for (JsonNode node : recordsNode) {
                MonitorKeywordRecord record = MAPPER.treeToValue(node, MonitorKeywordRecord.class);
                if (record == null || isBlank(record.key())) {
                    continue;
                }
                records.put(record.key(), record);
            }
            return records;
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read monitor tracking keywords: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private static void saveInternal(LinkedHashMap<String, MonitorKeywordRecord> records, String now) {
        Utils.ensureDirectory(STORAGE_FILE.toAbsolutePath().getParent());
        TrackingPayload payload = new TrackingPayload(SCHEMA_VERSION, now, new ArrayList<>(records.values()));
        try {
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(STORAGE_FILE, content + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write monitor tracking keywords: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private static void clearInternal() {
        try {
            Files.deleteIfExists(STORAGE_FILE);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to clear monitor tracking keywords: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private static ParsedKey parseKey(String value) {
        if (isBlank(value)) {
            return null;
        }
        int separator = value.indexOf('|');
        if (separator <= 0 || separator >= value.length() - 1) {
            return null;
        }
        String folderPath = value.substring(0, separator).trim();
        String keyword = value.substring(separator + 1).trim();
        if (folderPath.isBlank() || keyword.isBlank()) {
            return null;
        }
        return new ParsedKey(folderPath + "|" + keyword, folderPath, keyword);
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toUpperCase(Locale.ROOT);
    }

    private static String coalesce(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SyncResult(int totalRecords, int added, int touched, List<String> addedKeys) {
    }

    private record ParsedKey(String key, String folderPath, String keyword) {
    }

    private record TrackingPayload(int schemaVersion, String updatedAt, List<MonitorKeywordRecord> records) {
    }
}
