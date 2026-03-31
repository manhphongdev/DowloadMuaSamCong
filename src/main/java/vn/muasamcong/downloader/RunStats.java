package vn.muasamcong.downloader;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class RunStats {

    private final int totalKeywords;
    private final AtomicInteger successCount;
    private final AtomicInteger failCount;
    private final Queue<FailureRecord> failures;

    public RunStats(int totalKeywords) {
        this.totalKeywords = totalKeywords;
        this.successCount = new AtomicInteger();
        this.failCount = new AtomicInteger();
        this.failures = new ConcurrentLinkedQueue<>();
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

    public void addFailure(String keyword, int attempts, String reason, long threadId) {
        failures.add(new FailureRecord(keyword, attempts, reason, threadId));
    }

    public List<FailureRecord> getFailures() {
        return List.copyOf(failures);
    }

    public record FailureRecord(String keyword, int attempts, String reason, long threadId) {
    }
}
