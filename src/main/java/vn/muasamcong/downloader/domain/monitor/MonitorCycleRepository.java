package vn.muasamcong.downloader.domain.monitor;

import java.util.Optional;

public interface MonitorCycleRepository {

    void append(MonitorCycleLog log);

    Optional<MonitorCycleLog> latest();
}
