package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.domain.bidpackage.PackageSyncStatus;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.parser.FolderKeywordReader;

public final class PackageDiscoveryService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final PackageRepository packageRepository;

    public PackageDiscoveryService(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    public DiscoveryResult discover(List<Path> rootFolders) {
        List<KeywordTarget> targets = FolderKeywordReader.readKeywords(rootFolders);
        Map<String, BidPackage> existingById = new LinkedHashMap<>();
        for (BidPackage pkg : packageRepository.findAll()) {
            if (pkg != null && pkg.id() != null && !pkg.id().isBlank()) {
                existingById.put(pkg.id(), pkg);
            }
        }

        String now = LocalDateTime.now().format(TS);
        List<BidPackage> discovered = new ArrayList<>();
        for (KeywordTarget target : targets) {
            if (target == null || target.folderPath() == null || target.keyword() == null) {
                continue;
            }
            String folderPath = target.folderPath().toAbsolutePath().normalize().toString();
            String id = BidPackage.buildId(folderPath, target.keyword());
            BidPackage existing = existingById.get(id);
            if (existing != null) {
                discovered.add(existing);
                continue;
            }
            discovered.add(new BidPackage(
                id,
                folderPath,
                target.keyword().trim(),
                parentFolderName(target.folderPath()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                PackageSyncStatus.PENDING,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                null
            ));
        }
        packageRepository.saveAll(discovered);
        return new DiscoveryResult(discovered.size(), targets.size(), List.copyOf(discovered));
    }

    private static String parentFolderName(Path folderPath) {
        if (folderPath == null || folderPath.getParent() == null || folderPath.getParent().getFileName() == null) {
            return null;
        }
        return folderPath.getParent().getFileName().toString();
    }

    public record DiscoveryResult(int packageCount, int folderTargetCount, List<BidPackage> packages) {
    }
}
