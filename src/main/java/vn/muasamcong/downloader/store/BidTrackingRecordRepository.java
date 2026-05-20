package vn.muasamcong.downloader.store;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import vn.muasamcong.downloader.model.BidTrackingRecord;

public interface BidTrackingRecordRepository {
    void upsert(BidTrackingRecord record);

    List<BidTrackingRecord> findAll();

    Optional<BidTrackingRecord> findByKey(String key);

    int removeByKeys(Set<String> keys);

    void clear();
}
