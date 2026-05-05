package vn.muasamcong.downloader.core;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class RunStats {

    private final int totalKeywords;
    private final AtomicInteger successCount;
    private final AtomicInteger failCount;
    private final Queue<FailureRecord> failures;
    private final Queue<ProcessedRecord> processedRecords;

    public RunStats(int totalKeywords) {
        this.totalKeywords = totalKeywords;
        this.successCount = new AtomicInteger();
        this.failCount = new AtomicInteger();
        this.failures = new ConcurrentLinkedQueue<>();
        this.processedRecords = new ConcurrentLinkedQueue<>();
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

    public void addProcessedRecord(ProcessedRecord record) {
        if (record != null) {
            processedRecords.add(record);
        }
    }

    public List<ProcessedRecord> getProcessedRecords() {
        return List.copyOf(processedRecords);
    }

    public record FailureRecord(String keyword, int attempts, String reason, long threadId) {
    }

    public record ProcessedRecord(
        String keyword,
        String folderPath,
        String status,
        int attempts,
        String error,
        int downloadedFilesCount
    ) {
    }
}
