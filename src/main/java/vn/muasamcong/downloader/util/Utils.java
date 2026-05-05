package vn.muasamcong.downloader.util;

import vn.muasamcong.downloader.core.RunStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;

public final class Utils {

    private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicReference<Consumer<String>> LOG_SINK = new AtomicReference<>();
    private static final Path DATA_DIR = resolveDataDirectory();

    private Utils() {
    }

    public static void logStatus(String keyword, String status, int attempt, String message) {
        long threadId = Thread.currentThread().threadId();
        String timestamp = LocalDateTime.now().format(TS_FORMAT);
        String line = String.format(
            "%s | thread=%d | keyword=\"%s\" | attempt=%d | status=%s | %s",
            timestamp,
            threadId,
            keyword,
            attempt,
            status,
            message
        );
        logPlain(line);
    }

    public static void setLogSink(Consumer<String> sink) {
        LOG_SINK.set(sink);
    }

    public static void clearLogSink() {
        LOG_SINK.set(null);
    }

    public static void logPlain(String line) {
        LOGGER.info(line);
        Consumer<String> sink = LOG_SINK.get();
        if (sink != null) {
            sink.accept(line);
        }
    }

    public static Set<Path> snapshotPdfFiles(Path downloadDir) {
        ensureDirectory(downloadDir);
        try (Stream<Path> files = Files.list(downloadDir)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                .collect(Collectors.toCollection(HashSet::new));
        } catch (IOException ex) {
            throw new RuntimeException("Unable to list download directory: " + downloadDir.toAbsolutePath(), ex);
        }
    }

    public static Path waitForDownloadedPdf(Path downloadDir, Set<Path> filesBeforeClick, Duration timeout) {
        Wait<Path> wait = new FluentWait<>(downloadDir)
            .withTimeout(timeout)
            .pollingEvery(Duration.ofMillis(500))
            .ignoring(RuntimeException.class);

        return wait.until(path -> {
            if (hasInProgressDownload(path)) {
                return null;
            }

            Set<Path> current = snapshotPdfFiles(path);
            current.removeAll(filesBeforeClick == null ? Set.of() : filesBeforeClick);
            if (current.isEmpty()) {
                return null;
            }

            return current.stream()
                .filter(Utils::isNonEmptyPdf)
                .findFirst()
                .orElse(null);
        });
    }

    public static Path renamePdfByKeyword(Path sourcePdf, String keyword) {
        if (sourcePdf == null || !Files.exists(sourcePdf)) {
            throw new IllegalArgumentException("Source PDF is missing.");
        }

        String safeKeyword = sanitizeFileName(keyword);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path parent = sourcePdf.getParent();
        Path target = parent.resolve(safeKeyword + "_" + timestamp + ".pdf");
        int suffix = 1;

        while (Files.exists(target)) {
            target = parent.resolve(safeKeyword + "_" + timestamp + "_" + suffix + ".pdf");
            suffix++;
        }

        try {
            return Files.move(sourcePdf, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to rename downloaded PDF.", ex);
        }
    }

    public static Path movePdfToTargetFolder(Path sourcePdf, Path targetFolder) {
        if (sourcePdf == null || !Files.exists(sourcePdf)) {
            throw new IllegalArgumentException("Source PDF is missing.");
        }

        ensureDirectory(targetFolder);

        String fileName = sourcePdf.getFileName().toString();
        Path target = targetFolder.resolve(fileName);

        try {
            return Files.move(sourcePdf, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to move PDF to target folder.", ex);
        }
    }

    public static boolean isNonEmptyPdf(Path file) {
        if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
            return false;
        }
        if (!file.getFileName().toString().toLowerCase().endsWith(".pdf")) {
            return false;
        }

        try {
            return Files.size(file) > 0;
        } catch (IOException ex) {
            return false;
        }
    }

    public static String sanitizeFileName(String value) {
        String safe = Objects.requireNonNullElse(value, "keyword")
            .trim()
            .replaceAll("[\\\\/:*?\"<>|]", "_")
            .replaceAll("\\s+", "_")
            .replaceAll("_+", "_");

        if (safe.isBlank()) {
            return "keyword";
        }
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static String buildSuffixedFileName(String fileName, int suffix) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName + "_" + suffix;
        }

        String baseName = fileName.substring(0, dotIndex);
        String extension = fileName.substring(dotIndex);
        return baseName + "_" + suffix + extension;
    }

    public static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to create directory: " + path.toAbsolutePath(), ex);
        }
    }

    public static Path dataDirectory() {
        return DATA_DIR;
    }

    public static Path dataFile(String fileName) {
        return DATA_DIR.resolve(fileName);
    }

    public static void logFinalStats(RunStats stats) {
        String line = String.format(
            "Completed. Total=%d, Success=%d, Fail=%d",
            stats.getTotalKeywords(),
            stats.getSuccessCount(),
            stats.getFailCount()
        );
        logPlain(line);

        if (stats.getFailures().isEmpty()) {
            logPlain("Failed downloads: 0");
            return;
        }

        logPlain("Failed downloads:");
        int index = 1;
        for (RunStats.FailureRecord failure : stats.getFailures()) {
            String item = String.format(
                "%d) keyword=\"%s\" | attempts=%d | thread=%d | reason=%s",
                index,
                failure.keyword(),
                failure.attempts(),
                failure.threadId(),
                failure.reason()
            );
            logPlain(item);
            index++;
        }
    }

    private static boolean hasInProgressDownload(Path downloadDir) {
        try (Stream<Path> files = Files.list(downloadDir)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString().toLowerCase())
                .anyMatch(name -> name.endsWith(".crdownload") || name.endsWith(".tmp") || name.endsWith(".part"));
        } catch (IOException ex) {
            return false;
        }
    }

    public static Path safeWaitForPdf(Path downloadDir, Set<Path> filesBeforeClick, Duration timeout) {
        try {
            return waitForDownloadedPdf(downloadDir, filesBeforeClick, timeout);
        } catch (TimeoutException ex) {
            return null;
        }
    }

    public static void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort cleanup only.
                    }
                });
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }

    private static Path resolveDataDirectory() {
        String envDataDir = System.getenv("MUASAMCONG_DATA_DIR");
        if (envDataDir != null && !envDataDir.isBlank()) {
            return Path.of(envDataDir).toAbsolutePath().normalize();
        }

        Path workingDirData = Path.of("data").toAbsolutePath().normalize();
        if (Files.exists(workingDirData)) {
            return workingDirData;
        }

        Path packagedContentData = Path.of("content", "data").toAbsolutePath().normalize();
        if (Files.exists(packagedContentData)) {
            return packagedContentData;
        }

        return workingDirData;
    }
}
