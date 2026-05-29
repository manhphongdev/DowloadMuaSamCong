package vn.muasamcong.downloader.export;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import vn.muasamcong.downloader.core.AppFeatures;
import vn.muasamcong.downloader.util.Utils;

public final class EgpAgentFileDownloadService {

    private static final String AGENT_DOWNLOAD_URL =
        "http://127.0.0.1:1234/api/download/file/browser/public";
    private static final String ORIGIN = "https://muasamcong.mpi.gov.vn";
    private static final String REFERER =
        "https://muasamcong.mpi.gov.vn/web/guest/contractor-selection";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_BODY_SNIPPET = 160;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static volatile int maxConcurrency = AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_DEFAULT;
    private static volatile Semaphore downloadSlots = new Semaphore(maxConcurrency, true);

    public enum Outcome {
        SKIPPED_EXISTS,
        SKIPPED_INVALID_REF,
        DOWNLOADED,
        AGENT_UNAVAILABLE,
        FORBIDDEN,
        INVALID_BODY,
        FAILED
    }

    public record Result(Outcome outcome, Path targetFile, String message) {
    }

    private EgpAgentFileDownloadService() {
    }

    public static int getMaxConcurrency() {
        return maxConcurrency;
    }

    public static void setMaxConcurrency(int value) {
        int normalized = Math.max(
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_MIN,
            Math.min(AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_MAX, value)
        );
        maxConcurrency = normalized;
        downloadSlots = new Semaphore(normalized, true);
    }

    public static Result downloadIfMissing(Path outputDir, AgentFileRef ref) {
        if (outputDir == null || ref == null) {
            return new Result(Outcome.SKIPPED_INVALID_REF, null, "missing output dir or ref");
        }
        boolean acquired = false;
        try {
            downloadSlots.acquire();
            acquired = true;
            return downloadIfMissingInternal(outputDir, ref);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, null, "interrupted");
        } finally {
            if (acquired) {
                downloadSlots.release();
            }
        }
    }

    private static Result downloadIfMissingInternal(Path outputDir, AgentFileRef ref) {
        if (isBlank(ref.fileId())) {
            return new Result(Outcome.SKIPPED_INVALID_REF, null, "missing fileId");
        }
        String fileName = safeFileName(ref.fileName(), ref.fileId());
        Path dir = ref.subDir() == null ? outputDir : outputDir.resolve(ref.subDir());
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir.toAbsolutePath().normalize())) {
            return new Result(Outcome.FAILED, null, "unsafe target path");
        }
        if (artifactFileExists(target)) {
            return new Result(Outcome.SKIPPED_EXISTS, target, "already exists");
        }

        Utils.ensureDirectory(dir);
        Path temp = partPath(dir, fileName);
        Path promoted = tryPromotePartFile(temp, target);
        if (promoted != null) {
            Utils.logPlain("Agent file downloaded (from .part): " + promoted.toAbsolutePath());
            return new Result(Outcome.DOWNLOADED, promoted, "promoted partial");
        }
        int maxAttempts = Math.max(1, AppFeatures.AGENT_DOWNLOAD_MAX_ATTEMPTS);
        Result lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            deleteQuietly(temp);
            try {
                HttpResponse<InputStream> response = request(ref.fileId());
                int status = response.statusCode();
                String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
                if (status == 403) {
                    String detail = readErrorBody(response);
                    lastError = new Result(
                        Outcome.FORBIDDEN,
                        null,
                        "VNeGP agent returned 403 for fileId=" + ref.fileId()
                            + (detail == null || detail.isBlank() ? "" : " — " + detail)
                    );
                    if (retryAfterDelay(attempt, maxAttempts)) {
                        continue;
                    }
                    deleteQuietly(temp);
                    return lastError;
                }
                if (contentType.contains("json") || contentType.contains("text/html")) {
                    String detail = readErrorBody(response);
                    lastError = new Result(
                        Outcome.INVALID_BODY,
                        null,
                        "VNeGP agent returned " + contentType + " for fileId=" + ref.fileId()
                            + (detail == null || detail.isBlank() ? "" : " — " + detail)
                    );
                    if (retryAfterDelay(attempt, maxAttempts)) {
                        continue;
                    }
                    deleteQuietly(temp);
                    return lastError;
                }
                if (status < 200 || status >= 300) {
                    String detail = readErrorBody(response);
                    lastError = new Result(
                        Outcome.FAILED,
                        null,
                        "VNeGP agent HTTP " + status + " for fileId=" + ref.fileId()
                            + (detail == null || detail.isBlank() ? "" : " — " + detail)
                    );
                    if ((status == 429 || status == 400) && retryAfterDelay(attempt, maxAttempts)) {
                        continue;
                    }
                    deleteQuietly(temp);
                    return lastError;
                }
                try (InputStream body = response.body()) {
                    Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!isValidDownloadedFile(temp)) {
                    String snippet = peekFileSnippet(temp);
                    lastError = new Result(
                        Outcome.INVALID_BODY,
                        null,
                        "Downloaded file invalid or empty: " + fileName
                            + (snippet == null || snippet.isBlank()
                            ? " (agent may need portal session — open muasamcong once)"
                            : " — " + snippet)
                    );
                    if (retryAfterDelay(attempt, maxAttempts)) {
                        continue;
                    }
                    deleteQuietly(temp);
                    return lastError;
                }
                commitDownload(temp, target);
                Utils.logPlain("Agent file downloaded: " + target.toAbsolutePath());
                return new Result(Outcome.DOWNLOADED, target, "downloaded");
            } catch (ConnectException ex) {
                deleteQuietly(temp);
                return new Result(
                    Outcome.AGENT_UNAVAILABLE,
                    null,
                    "VNeGP agent not reachable at 127.0.0.1:1234 — " + ex.getMessage()
                );
            } catch (IOException ex) {
                deleteQuietly(temp);
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                if (message.toLowerCase(Locale.ROOT).contains("connection refused")) {
                    return new Result(Outcome.AGENT_UNAVAILABLE, null, "VNeGP agent connection refused");
                }
                lastError = new Result(Outcome.FAILED, null, message);
                if (retryAfterDelay(attempt, maxAttempts)) {
                    continue;
                }
                deleteQuietly(temp);
                return lastError;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                deleteQuietly(temp);
                return new Result(Outcome.FAILED, null, "interrupted");
            } catch (Exception ex) {
                deleteQuietly(temp);
                lastError = new Result(
                    Outcome.FAILED,
                    null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                );
                if (retryAfterDelay(attempt, maxAttempts)) {
                    continue;
                }
                deleteQuietly(temp);
                return lastError;
            }
        }

        deleteQuietly(temp);
        return lastError == null
            ? new Result(Outcome.FAILED, null, "download failed")
            : lastError;
    }

    private static Path partPath(Path dir, String fileName) {
        return dir.resolve(fileName + ".part");
    }

    private static Path tryPromotePartFile(Path part, Path target) {
        if (!Files.isRegularFile(part) || artifactFileExists(target)) {
            return null;
        }
        if (!isValidDownloadedFile(part)) {
            return null;
        }
        try {
            commitDownload(part, target);
            return target;
        } catch (IOException ex) {
            return null;
        }
    }

    private static void commitDownload(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveEx) {
            Files.copy(temp, target, StandardCopyOption.REPLACE_EXISTING);
            deleteQuietly(temp);
        }
    }

    public static boolean artifactFileExists(Path file) {
        return file != null && Files.isRegularFile(file);
    }

    public static boolean allRefsPresentOnDisk(Path outputDir, Iterable<AgentFileRef> refs) {
        if (outputDir == null || refs == null) {
            return true;
        }
        boolean any = false;
        for (AgentFileRef ref : refs) {
            if (ref == null || isBlank(ref.fileId())) {
                continue;
            }
            any = true;
            Path dir = ref.subDir() == null ? outputDir : outputDir.resolve(ref.subDir());
            Path target = dir.resolve(safeFileName(ref.fileName(), ref.fileId()));
            if (!artifactFileExists(target)) {
                return false;
            }
        }
        return any;
    }

    public static boolean anyRefMissingOnDisk(Path outputDir, Iterable<AgentFileRef> refs) {
        if (outputDir == null || refs == null) {
            return false;
        }
        for (AgentFileRef ref : refs) {
            if (ref == null || isBlank(ref.fileId())) {
                continue;
            }
            Path dir = ref.subDir() == null ? outputDir : outputDir.resolve(ref.subDir());
            Path target = dir.resolve(safeFileName(ref.fileName(), ref.fileId()));
            if (!artifactFileExists(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean retryAfterDelay(int attempt, int maxAttempts) {
        if (attempt >= maxAttempts || Thread.currentThread().isInterrupted()) {
            return false;
        }
        try {
            long base = Math.max(250L, AppFeatures.AGENT_DOWNLOAD_RETRY_BASE_MS);
            Thread.sleep(base * attempt);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String peekFileSnippet(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return "empty body";
            }
            int len = Math.min(bytes.length, MAX_BODY_SNIPPET);
            String text = new String(bytes, 0, len, StandardCharsets.UTF_8).trim();
            if (text.length() > MAX_BODY_SNIPPET) {
                return text.substring(0, MAX_BODY_SNIPPET) + "...";
            }
            return text;
        } catch (IOException ex) {
            return null;
        }
    }

    private static HttpResponse<InputStream> request(String fileId)
        throws IOException, InterruptedException {
        String encodedId = URLEncoder.encode(fileId, StandardCharsets.UTF_8);
        URI uri = URI.create(
            AGENT_DOWNLOAD_URL + "?fileId=" + encodedId + "&downloadFilePublic=true"
        );
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/octet-stream, application/pdf, */*")
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String readErrorBody(HttpResponse<InputStream> response) {
        if (response == null || response.body() == null) {
            return null;
        }
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(512);
            if (bytes.length == 0) {
                return null;
            }
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{") && text.length() > 120) {
                return text.substring(0, 120) + "...";
            }
            return text;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isValidDownloadedFile(Path file) {
        if (!Utils.isNonEmptyRegularFile(file)) {
            return false;
        }
        if (looksLikeJsonError(file)) {
            return false;
        }
        if (looksLikePdf(file)) {
            return true;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return Utils.isNonEmptyPdf(file);
        }
        return true;
    }

    private static boolean looksLikePdf(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(4);
            return header.length == 4
                && header[0] == '%'
                && header[1] == 'P'
                && header[2] == 'D'
                && header[3] == 'F';
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean looksLikeJsonError(Path file) {
        try {
            String start = Files.readString(file, StandardCharsets.UTF_8).stripLeading();
            return start.startsWith("{") && start.contains("\"status\"");
        } catch (IOException ex) {
            return false;
        }
    }

    private static String safeFileName(String fileName, String fileId) {
        String name = fileName == null || fileName.isBlank() ? fileId + ".bin" : fileName.trim();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "undefined".equalsIgnoreCase(value.trim());
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
