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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.muasamcong.downloader.model.BidTrackingRecord;
import vn.muasamcong.downloader.util.Utils;

public final class JsonBidTrackingRecordRepository implements BidTrackingRecordRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 1;
    private static final Path TRACKING_FILE = Utils.dataFile("bid_tracking_records.json");
    private final Object lock = new Object();

    @Override
    public void upsert(BidTrackingRecord record) {
        if (record == null || isBlank(record.key())) {
            return;
        }
        synchronized (lock) {
            LinkedHashMap<String, BidTrackingRecord> records = loadRecordsByKeyInternal();
            records.put(record.key(), record);
            saveInternal(records);
        }
    }

    @Override
    public List<BidTrackingRecord> findAll() {
        synchronized (lock) {
            return List.copyOf(loadRecordsByKeyInternal().values());
        }
    }

    @Override
    public Optional<BidTrackingRecord> findByKey(String key) {
        if (isBlank(key)) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(loadRecordsByKeyInternal().get(key));
        }
    }

    @Override
    public int removeByKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        synchronized (lock) {
            LinkedHashMap<String, BidTrackingRecord> records = loadRecordsByKeyInternal();
            int before = records.size();
            records.keySet().removeIf(keys::contains);
            int removed = before - records.size();
            if (removed > 0) {
                saveOrDeleteInternal(records);
            }
            return removed;
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(TRACKING_FILE);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to clear bid tracking records: " + TRACKING_FILE.toAbsolutePath(), ex);
            }
        }
    }

    private LinkedHashMap<String, BidTrackingRecord> loadRecordsByKeyInternal() {
        LinkedHashMap<String, BidTrackingRecord> records = new LinkedHashMap<>();
        if (!Files.exists(TRACKING_FILE)) {
            return records;
        }

        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(TRACKING_FILE));
            JsonNode recordsNode = root == null ? null : root.get("records");
            if (recordsNode == null || !recordsNode.isArray()) {
                return records;
            }
            for (JsonNode node : recordsNode) {
                BidTrackingRecord record = MAPPER.treeToValue(node, BidTrackingRecord.class);
                if (record != null && !isBlank(record.key())) {
                    records.put(record.key(), record);
                }
            }
            return records;
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read bid tracking records: " + TRACKING_FILE.toAbsolutePath(), ex);
        }
    }

    private void saveOrDeleteInternal(LinkedHashMap<String, BidTrackingRecord> records) {
        if (records.isEmpty()) {
            try {
                Files.deleteIfExists(TRACKING_FILE);
                return;
            } catch (IOException ex) {
                throw new RuntimeException("Unable to update bid tracking records: " + TRACKING_FILE.toAbsolutePath(), ex);
            }
        }
        saveInternal(records);
    }

    private void saveInternal(LinkedHashMap<String, BidTrackingRecord> records) {
        Utils.ensureDirectory(TRACKING_FILE.toAbsolutePath().getParent());
        TrackingPayload payload = new TrackingPayload(
            SCHEMA_VERSION,
            LocalDateTime.now().format(TS),
            new ArrayList<>(records.values())
        );
        try {
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(TRACKING_FILE, content + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write bid tracking records: " + TRACKING_FILE.toAbsolutePath(), ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record TrackingPayload(
        int schemaVersion,
        String updatedAt,
        List<BidTrackingRecord> records
    ) {
    }
}
