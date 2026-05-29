package vn.muasamcong.downloader.application.sheet;

import java.nio.file.Path;
import vn.muasamcong.downloader.export.GoogleSheetsSyncService;
import vn.muasamcong.downloader.store.GoogleSheetsConfigStore;

public final class SheetPublishService {

    /**
     * Pushes bid_sheet_rows.json to Google Sheets when auto sync is enabled in Sheets config.
     *
     * @return true if a sync was attempted, false if skipped (auto sync off or missing config)
     */
    public boolean publishIfEnabled(Path bidRowsJsonFile) {
        return GoogleSheetsSyncService.syncFromJsonIfEnabled(bidRowsJsonFile);
    }

    public boolean isAutoSyncEnabled() {
        return GoogleSheetsConfigStore.resolve().autoSync();
    }
}
