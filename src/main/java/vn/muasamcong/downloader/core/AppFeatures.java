package vn.muasamcong.downloader.core;

/**
 * Feature flags. Set {@link #SELENIUM_DOWNLOAD_ENABLED} to {@code true} to re-enable RUN / Auto Run.
 */
public final class AppFeatures {

    public static final boolean SELENIUM_DOWNLOAD_ENABLED = false;

    /** HTTP file download via VNeGP agent during Monitor API sync (phase 1). */
    public static final boolean MONITOR_AGENT_FILE_DOWNLOAD = true;

  /** Default max concurrent VNeGP agent downloads (overridable in Monitor UI). */
    public static final int AGENT_DOWNLOAD_MAX_CONCURRENCY_DEFAULT = 3;

    /** Min/max for agent download concurrency spinner in UI. */
    public static final int AGENT_DOWNLOAD_MAX_CONCURRENCY_MIN = 1;
    public static final int AGENT_DOWNLOAD_MAX_CONCURRENCY_MAX = 8;

    /** Default parallel Chrome sessions for Monitor Selenium (BBMT) downloads. */
    public static final int SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT = 2;

    public static final int SELENIUM_DOWNLOAD_MAX_CONCURRENCY_MIN = 1;
    public static final int SELENIUM_DOWNLOAD_MAX_CONCURRENCY_MAX = 10;

    /** Retries per file when agent returns 403 or a non-PDF body. */
    public static final int AGENT_DOWNLOAD_MAX_ATTEMPTS = 3;

    /** Pause between agent download attempts (ms). */
    public static final long AGENT_DOWNLOAD_RETRY_BASE_MS = 750;

    /** Push Google Sheet from package store every N minutes while monitor scheduler is on. */
    public static final int MONITOR_SHEET_EXPORT_INTERVAL_MINUTES = 10;

    /** API sync workers when agent file download is enabled. */
    public static final int MONITOR_API_SYNC_PARALLELISM_WITH_AGENT = 2;

    public static final int MONITOR_API_SYNC_PARALLELISM_DEFAULT = 3;

    /**
     * Allows Monitor phase 2 (Selenium file download). Off by default when agent download is enabled.
     */
    public static final boolean MONITOR_SELENIUM_FALLBACK = false;

    /** Monitor phase: Selenium download for BBMT PDF (portal). Toggle via {@code bbmtSeleniumDownload}. */
    public static final boolean MONITOR_BBMT_SELENIUM = true;

    /** Legacy flag: phase 2 Selenium path (only when {@link #MONITOR_SELENIUM_FALLBACK} is true). */
    public static final boolean MONITOR_SELENIUM_AFTER_SHEET = MONITOR_SELENIUM_FALLBACK;

    private AppFeatures() {
    }

    public static boolean isDownloadEnabled() {
        return SELENIUM_DOWNLOAD_ENABLED;
    }

    public static boolean isMonitorAgentFileDownloadEnabled() {
        return MONITOR_AGENT_FILE_DOWNLOAD;
    }

    public static boolean isMonitorSeleniumFallbackEnabled() {
        return MONITOR_SELENIUM_FALLBACK;
    }

    public static boolean isMonitorSeleniumAfterSheetEnabled() {
        return MONITOR_SELENIUM_AFTER_SHEET;
    }

    public static boolean isMonitorBbmtSeleniumEnabled() {
        return MONITOR_BBMT_SELENIUM;
    }
}
