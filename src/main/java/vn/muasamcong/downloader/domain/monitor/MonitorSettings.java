package vn.muasamcong.downloader.domain.monitor;

import vn.muasamcong.downloader.core.AppFeatures;

public record MonitorSettings(
    boolean enabled,
    int intervalMinutes,
    boolean downloadFilesAfterSheet,
    int agentDownloadConcurrency,
    boolean bbmtSeleniumDownload,
    int seleniumDownloadConcurrency
) {

    public MonitorSettings(boolean enabled, int intervalMinutes) {
        this(
            enabled,
            intervalMinutes,
            false,
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_DEFAULT,
            true,
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT
        );
    }

    public MonitorSettings(boolean enabled, int intervalMinutes, boolean downloadFilesAfterSheet) {
        this(
            enabled,
            intervalMinutes,
            downloadFilesAfterSheet,
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_DEFAULT,
            true,
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT
        );
    }

    public MonitorSettings(
        boolean enabled,
        int intervalMinutes,
        boolean downloadFilesAfterSheet,
        int agentDownloadConcurrency
    ) {
        this(
            enabled,
            intervalMinutes,
            downloadFilesAfterSheet,
            agentDownloadConcurrency,
            true,
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT
        );
    }

    public MonitorSettings(
        boolean enabled,
        int intervalMinutes,
        boolean downloadFilesAfterSheet,
        int agentDownloadConcurrency,
        boolean bbmtSeleniumDownload
    ) {
        this(
            enabled,
            intervalMinutes,
            downloadFilesAfterSheet,
            agentDownloadConcurrency,
            bbmtSeleniumDownload,
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT
        );
    }

    public static MonitorSettings defaults() {
        return new MonitorSettings(
            true,
            30,
            true,
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_DEFAULT,
            true,
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT
        );
    }

    public MonitorSettings normalized() {
        int interval = Math.max(5, Math.min(1440, intervalMinutes));
        int agentThreads = Math.max(
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_MIN,
            Math.min(AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_MAX, agentDownloadConcurrency)
        );
        int seleniumThreads = Math.max(
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_MIN,
            Math.min(AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_MAX, seleniumDownloadConcurrency)
        );
        return new MonitorSettings(
            enabled,
            interval,
            downloadFilesAfterSheet,
            agentThreads,
            bbmtSeleniumDownload,
            seleniumThreads
        );
    }
}
