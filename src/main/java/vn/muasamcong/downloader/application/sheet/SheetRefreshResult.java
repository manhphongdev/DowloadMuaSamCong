package vn.muasamcong.downloader.application.sheet;

import java.util.List;

public record SheetRefreshResult(
    int rowCount,
    List<String> sheetDataChangedKeys,
    boolean sheetChanged,
    boolean interrupted
) {

    public SheetRefreshResult(int rowCount, List<String> sheetDataChangedKeys) {
        this(rowCount, sheetDataChangedKeys, sheetDataChangedKeys != null && !sheetDataChangedKeys.isEmpty(), false);
    }

    public SheetRefreshResult(int rowCount, List<String> sheetDataChangedKeys, boolean sheetChanged) {
        this(rowCount, sheetDataChangedKeys, sheetChanged, false);
    }
}
