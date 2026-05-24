package vn.muasamcong.downloader.domain.bidpackage;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PackageRepository {

    Optional<BidPackage> findById(String id);

    List<BidPackage> findAll();

    void save(BidPackage pkg);

    void saveAll(List<BidPackage> packages);

    int deleteByIds(Set<String> ids);
}
