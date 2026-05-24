package vn.muasamcong.downloader.application.sheet;

import java.nio.file.Path;
import vn.muasamcong.downloader.export.GoogleSheetsSyncService;

public final class SheetPublishService {

    public void publishIfEnabled(Path bidRowsJsonFile) {
        GoogleSheetsSyncService.syncFromJsonIfEnabled(bidRowsJsonFile);
    }
}
