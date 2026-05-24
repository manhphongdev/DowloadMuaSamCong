package vn.muasamcong.downloader.core;

/**
 * Feature flags. Set {@link #SELENIUM_DOWNLOAD_ENABLED} to {@code true} to re-enable RUN / Auto Run.
 */
public final class AppFeatures {

    public static final boolean SELENIUM_DOWNLOAD_ENABLED = false;

    /** Allows Monitor phase 2 (Selenium file download after sheet sync). */
    public static final boolean MONITOR_SELENIUM_AFTER_SHEET = true;

    private AppFeatures() {
    }

    public static boolean isDownloadEnabled() {
        return SELENIUM_DOWNLOAD_ENABLED;
    }

    public static boolean isMonitorSeleniumAfterSheetEnabled() {
        return MONITOR_SELENIUM_AFTER_SHEET;
    }
}
