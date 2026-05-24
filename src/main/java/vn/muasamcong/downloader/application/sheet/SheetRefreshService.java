package vn.muasamcong.downloader.application.sheet;

import vn.muasamcong.downloader.export.BidSheetApiSyncService;

public final class SheetRefreshService {

    public SheetRefreshResult refreshRowsFromTracking() {
        return BidSheetApiSyncService.refreshBidSheetRowsFromTracking();
    }
}
