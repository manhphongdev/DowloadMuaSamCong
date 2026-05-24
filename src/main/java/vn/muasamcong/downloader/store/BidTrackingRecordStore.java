package vn.muasamcong.downloader.store;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.muasamcong.downloader.model.BidApiParams;
import vn.muasamcong.downloader.model.BidTrackingRecord;
import vn.muasamcong.downloader.model.KeywordTarget;

public final class BidTrackingRecordStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final BidTrackingRecordRepository REPOSITORY = new JsonBidTrackingRecordRepository();

    private BidTrackingRecordStore() {
    }

    public static void upsertFromDetailUrl(KeywordTarget target, String detailUrl) {
        if (target == null || isBlank(target.keyword()) || isBlank(detailUrl)) {
            return;
        }

        String key = buildTargetKey(target);
        String now = LocalDateTime.now().format(TS);
        String folderPath = target.folderPath() == null
            ? null
            : target.folderPath().toAbsolutePath().normalize().toString();
        Optional<BidTrackingRecord> existing = REPOSITORY.findByKey(key);
        String firstSeenAt = existing.map(BidTrackingRecord::firstSeenAt).orElse(now);
        BidTrackingRecord record = new BidTrackingRecord(
            key,
            folderPath,
            target.keyword().trim(),
            detailUrl,
            firstSeenAt,
            now,
            toApiParams(detailUrl)
        );
        REPOSITORY.upsert(record);
    }

    public static void upsertFromResolved(
        String folderPath,
        String keyword,
        String detailUrl,
        BidApiParams apiParams,
        String createdAt
    ) {
        if (isBlank(keyword) || apiParams == null) {
            return;
        }
        String normalizedFolder = folderPath == null || folderPath.isBlank()
            ? ""
            : java.nio.file.Path.of(folderPath).toAbsolutePath().normalize().toString();
        String key = normalizedFolder + "|" + keyword.trim();
        String now = LocalDateTime.now().format(TS);
        Optional<BidTrackingRecord> existing = REPOSITORY.findByKey(key);
        String firstSeenAt = existing.map(BidTrackingRecord::firstSeenAt)
            .filter(s -> !isBlank(s))
            .orElse(createdAt == null || createdAt.isBlank() ? now : createdAt);
        BidTrackingRecord record = new BidTrackingRecord(
            key,
            normalizedFolder.isBlank() ? folderPath : normalizedFolder,
            keyword.trim(),
            detailUrl,
            firstSeenAt,
            now,
            apiParams
        );
        REPOSITORY.upsert(record);
    }

    public static List<BidTrackingRecord> loadRecords() {
        return REPOSITORY.findAll();
    }

    public static int removeByKeys(Set<String> keys) {
        return REPOSITORY.removeByKeys(keys);
    }

    public static void clear() {
        REPOSITORY.clear();
    }

    public static BidApiParams apiParamsFromDetailUrl(String detailUrl) {
        return toApiParams(detailUrl);
    }

    private static BidApiParams toApiParams(String detailUrl) {
        Map<String, String> query = parseQuery(detailUrl);
        return new BidApiParams(
            query.get("notifyNo"),
            query.get("id"),
            query.get("notifyId"),
            query.get("inputResultId"),
            query.get("bidOpenId"),
            query.get("techReqId"),
            query.get("bidPreNotifyResultId"),
            query.get("bidPreOpenId"),
            query.get("processApply"),
            query.get("bidMode"),
            query.get("bidForm"),
            query.get("planNo"),
            query.get("stepCode"),
            query.get("isInternet")
        );
    }

    private static Map<String, String> parseQuery(String url) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (url == null) {
            return values;
        }
        int question = url.indexOf('?');
        if (question < 0 || question >= url.length() - 1) {
            return values;
        }
        String query = url.substring(question + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) {
            query = query.substring(0, fragment);
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawKey = equals >= 0 ? pair.substring(0, equals) : pair;
            String rawValue = equals >= 0 ? pair.substring(equals + 1) : "";
            String key = decode(rawKey);
            if (!key.isBlank()) {
                values.put(key, decode(rawValue));
            }
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String buildTargetKey(KeywordTarget target) {
        String folder = target.folderPath() == null
            ? ""
            : target.folderPath().toAbsolutePath().normalize().toString();
        return folder + "|" + target.keyword().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
