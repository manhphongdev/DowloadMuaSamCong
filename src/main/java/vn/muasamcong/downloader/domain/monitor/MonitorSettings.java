package vn.muasamcong.downloader.domain.monitor;

public record MonitorSettings(
    boolean enabled,
    int intervalMinutes,
    boolean downloadFilesAfterSheet
) {

    public MonitorSettings(boolean enabled, int intervalMinutes) {
        this(enabled, intervalMinutes, false);
    }

    public static MonitorSettings defaults() {
        return new MonitorSettings(false, 30, false);
    }

    public MonitorSettings normalized() {
        int interval = Math.max(5, Math.min(1440, intervalMinutes));
        return new MonitorSettings(enabled, interval, downloadFilesAfterSheet);
    }
}
