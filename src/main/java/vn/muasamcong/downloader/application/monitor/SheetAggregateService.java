package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.muasamcong.downloader.application.sheet.SheetRefreshResult;
import vn.muasamcong.downloader.core.DownloadWorker;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.model.BidSheetRow;
import vn.muasamcong.downloader.util.Utils;

public final class SheetAggregateService {

    private final PackageRepository packageRepository;

    public SheetAggregateService(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    public SheetRefreshResult aggregateToJson(Path bidRowsJsonFile, Set<String> changedPackageIds) {
        if (Thread.currentThread().isInterrupted()) {
            return new SheetRefreshResult(0, List.of(), false, true);
        }

        Set<String> changedIds = changedPackageIds == null ? Set.of() : changedPackageIds;
        Set<String> existingRowKeys = loadExistingRowKeys(bidRowsJsonFile);
        Set<String> newRowKeys = new LinkedHashSet<>();
        List<BidSheetRow> rows = new ArrayList<>();
        List<String> sheetDataChangedKeys = new ArrayList<>();

        for (BidPackage pkg : packageRepository.findAll()) {
            if (pkg == null || pkg.sheetRow() == null) {
                continue;
            }
            BidSheetRow row = pkg.sheetRow();
            rows.add(row);
            newRowKeys.add(rowKey(row));
            if (changedIds.contains(pkg.id())) {
                sheetDataChangedKeys.add(pkg.id());
            }
        }

        boolean rowSetChanged = !existingRowKeys.equals(newRowKeys);
        boolean sheetChanged = rowSetChanged || !sheetDataChangedKeys.isEmpty();
        if (sheetChanged) {
            DownloadWorker.replaceBidInfoRows(rows);
        } else {
            Utils.logPlain("Bid sheet rows unchanged. Skipping bid_sheet_rows.json write.");
        }
        return new SheetRefreshResult(rows.size(), List.copyOf(sheetDataChangedKeys), sheetChanged, false);
    }

    /** Writes all in-memory package rows to bid_sheet_rows.json (used for periodic sheet export). */
    public SheetRefreshResult writeAllRowsToJson(Path bidRowsJsonFile) {
        if (Thread.currentThread().isInterrupted()) {
            return new SheetRefreshResult(0, List.of(), false, true);
        }
        List<BidSheetRow> rows = new ArrayList<>();
        for (BidPackage pkg : packageRepository.findAll()) {
            if (pkg != null && pkg.sheetRow() != null) {
                rows.add(pkg.sheetRow());
            }
        }
        DownloadWorker.replaceBidInfoRows(rows);
        Utils.logPlain("Bid sheet rows written for export. rows=" + rows.size());
        return new SheetRefreshResult(rows.size(), List.of(), true, false);
    }

    private static Set<String> loadExistingRowKeys(Path jsonFile) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Path file = jsonFile == null ? Utils.dataFile("bid_sheet_rows.json") : jsonFile;
        if (!Files.exists(file)) {
            return keys;
        }
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.newBufferedReader(file));
            var rows = root == null ? null : (root.isArray() ? root : root.path("rows"));
            if (rows == null || !rows.isArray()) {
                return keys;
            }
            for (var row : rows) {
                keys.add(rowKey(
                    text(row, "tmbtNumber"),
                    text(row, "parentFolderName"),
                    text(row, "contractPerformanceFolder")
                ));
            }
        } catch (Exception ex) {
            Utils.logPlain("Unable to load existing bid sheet row keys: " + ex.getMessage());
        }
        return keys;
    }

    private static String rowKey(BidSheetRow row) {
        return rowKey(row.tmbtNumber(), row.parentFolderName(), row.contractPerformanceFolder());
    }

    private static String rowKey(String tmbtNumber, String parentFolderName, String contractFolderName) {
        return String.join("|",
            tmbtNumber == null ? "" : tmbtNumber,
            parentFolderName == null ? "" : parentFolderName,
            contractFolderName == null ? "" : contractFolderName
        );
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        var value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
