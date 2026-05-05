package vn.muasamcong.downloader.app;

import java.nio.file.Path;
import vn.muasamcong.downloader.export.GoogleSheetsSyncService;
import vn.muasamcong.downloader.util.Utils;

public final class ExportBidSheetMain {

    private ExportBidSheetMain() {
    }

    public static void main(String[] args) throws Exception {
        Path json = Utils.dataFile("bid_sheet_rows.json");
        GoogleSheetsSyncService.syncFromJsonIfEnabled(json);
        System.out.println("Exported data to Google Sheets (if enabled). Source: " + json.toAbsolutePath());
    }
}
