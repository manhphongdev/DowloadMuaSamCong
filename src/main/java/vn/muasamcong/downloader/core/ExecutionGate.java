package vn.muasamcong.downloader.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide guard so monitor (API-only) and download (Selenium) cycles do not overlap.
 */
public final class ExecutionGate {

    private static final AtomicInteger monitorHolds = new AtomicInteger();
    private static final AtomicInteger downloadHolds = new AtomicInteger();

    private ExecutionGate() {
    }

    public static boolean tryBeginMonitor() {
        if (isDownloadBusy()) {
            return false;
        }
        monitorHolds.incrementAndGet();
        return true;
    }

    public static void endMonitor() {
        monitorHolds.updateAndGet(value -> Math.max(0, value - 1));
    }

    public static boolean tryBeginDownload() {
        if (isMonitorBusy()) {
            return false;
        }
        downloadHolds.incrementAndGet();
        return true;
    }

    public static void endDownload() {
        downloadHolds.updateAndGet(value -> Math.max(0, value - 1));
    }

    public static boolean isMonitorBusy() {
        return monitorHolds.get() > 0;
    }

    public static boolean isDownloadBusy() {
        return downloadHolds.get() > 0;
    }

    public static boolean isBusy() {
        return isMonitorBusy() || isDownloadBusy();
    }
}
