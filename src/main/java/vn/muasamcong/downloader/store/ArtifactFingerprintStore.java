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
import java.util.Set;
import vn.muasamcong.downloader.model.ArtifactFingerprints;
import vn.muasamcong.downloader.util.Utils;

public final class ArtifactFingerprintStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 1;
    private static final Path STORAGE_FILE = Utils.dataFile("monitor_artifact_fingerprints.json");
    private static final Object LOCK = new Object();

    private ArtifactFingerprintStore() {
    }

    public static ArtifactFingerprints find(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        synchronized (LOCK) {
            return loadByKeyInternal().get(key);
        }
    }

    public static boolean isDownloadHintsUnchanged(String key, String hash) {
        ArtifactFingerprints existing = find(key);
        return hash != null && existing != null && hash.equals(existing.downloadHintsHash());
    }

    public static void updateHashes(
        String key,
        String sheetRowHash,
        String contractorCsvHash,
        String goodsExcelHash,
        String downloadHintsHash
    ) {
        if (key == null || key.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            LinkedHashMap<String, ArtifactFingerprints> records = loadByKeyInternal();
            ArtifactFingerprints existing = records.get(key);
            String now = now();
            boolean changed = existing == null
                || changed(existing.sheetRowHash(), sheetRowHash)
                || changed(existing.contractorCsvHash(), contractorCsvHash)
                || changed(existing.goodsExcelHash(), goodsExcelHash)
                || changed(existing.downloadHintsHash(), downloadHintsHash);
            records.put(key, new ArtifactFingerprints(
                key,
                coalesce(sheetRowHash, existing == null ? null : existing.sheetRowHash()),
                coalesce(contractorCsvHash, existing == null ? null : existing.contractorCsvHash()),
                coalesce(goodsExcelHash, existing == null ? null : existing.goodsExcelHash()),
                coalesce(downloadHintsHash, existing == null ? null : existing.downloadHintsHash()),
                now,
                changed ? now : existing.lastArtifactChangedAt()
            ));
            saveInternal(records, now);
        }
    }

    public static boolean isHashUnchanged(String key, HashType type, String hash) {
        ArtifactFingerprints existing = find(key);
        if (existing == null || hash == null) {
            return false;
        }
        return switch (type) {
            case SHEET_ROW -> hash.equals(existing.sheetRowHash());
            case CONTRACTOR_CSV -> hash.equals(existing.contractorCsvHash());
            case GOODS_EXCEL -> hash.equals(existing.goodsExcelHash());
            case DOWNLOAD_HINTS -> hash.equals(existing.downloadHintsHash());
        };
    }

    public static int removeByKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        synchronized (LOCK) {
            LinkedHashMap<String, ArtifactFingerprints> records = loadByKeyInternal();
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

    private static LinkedHashMap<String, ArtifactFingerprints> loadByKeyInternal() {
        LinkedHashMap<String, ArtifactFingerprints> records = new LinkedHashMap<>();
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
                ArtifactFingerprints record = MAPPER.treeToValue(node, ArtifactFingerprints.class);
                if (record != null && record.key() != null && !record.key().isBlank()) {
                    records.put(record.key(), record);
                }
            }
            return records;
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read artifact fingerprints: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private static void saveInternal(LinkedHashMap<String, ArtifactFingerprints> records, String now) {
        Utils.ensureDirectory(STORAGE_FILE.toAbsolutePath().getParent());
        Payload payload = new Payload(SCHEMA_VERSION, now, new ArrayList<>(records.values()));
        try {
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(STORAGE_FILE, content + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write artifact fingerprints: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private static void clearInternal() {
        try {
            Files.deleteIfExists(STORAGE_FILE);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to clear artifact fingerprints: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private static boolean changed(String oldHash, String newHash) {
        return newHash != null && !newHash.equals(oldHash);
    }

    private static String coalesce(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    public enum HashType {
        SHEET_ROW,
        CONTRACTOR_CSV,
        GOODS_EXCEL,
        DOWNLOAD_HINTS
    }

    private record Payload(int schemaVersion, String updatedAt, List<ArtifactFingerprints> records) {
    }
}
