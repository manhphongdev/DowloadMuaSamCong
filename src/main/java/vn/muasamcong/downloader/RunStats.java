package vn.muasamcong.downloader;

import java.util.concurrent.atomic.AtomicInteger;

public final class RunStats {

    private final int totalKeywords;
    private final AtomicInteger successCount;
    private final AtomicInteger failCount;

    public RunStats(int totalKeywords) {
        this.totalKeywords = totalKeywords;
        this.successCount = new AtomicInteger();
        this.failCount = new AtomicInteger();
    }

    public int getTotalKeywords() {
        return totalKeywords;
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getFailCount() {
        return failCount.get();
    }

    public void markSuccess() {
        successCount.incrementAndGet();
    }

    public void markFail() {
        failCount.incrementAndGet();
    }
}
