package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.util.List;

public record MonitorCycleRequest(
    List<Path> selectedRoots,
    boolean downloadFilesAfterSheet,
    int downloadConcurrency,
    boolean bbmtSeleniumDownload
) {

    public MonitorCycleRequest(List<Path> selectedRoots) {
        this(selectedRoots, false, 1, true);
    }

    public MonitorCycleRequest {
        selectedRoots = selectedRoots == null ? List.of() : List.copyOf(selectedRoots);
        downloadConcurrency = Math.max(1, Math.min(10, downloadConcurrency));
    }
}
