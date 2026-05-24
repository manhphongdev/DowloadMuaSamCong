package vn.muasamcong.downloader.domain.monitor;

public interface MonitorSettingsRepository {

    MonitorSettings load();

    void save(MonitorSettings settings);
}
