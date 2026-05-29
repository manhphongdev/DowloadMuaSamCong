package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import vn.muasamcong.downloader.util.Utils;

final class ApiJsonHttpClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 750L;

    private ApiJsonHttpClient() {
    }

    static JsonNode postJson(HttpClient client, ObjectMapper mapper, URI uri, String body, Duration timeout, String label) {
        return postJson(client, mapper, uri, body, timeout, label, true);
    }

    static JsonNode postJson(
        HttpClient client,
        ObjectMapper mapper,
        URI uri,
        String body,
        Duration timeout,
        String label,
        boolean logFailures
    ) {
        if (client == null || mapper == null || uri == null || body == null) {
            return null;
        }

        String context = label == null || label.isBlank() ? uri.toString() : label;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Origin", "https://muasamcong.mpi.gov.vn")
                    .header("Referer", "https://muasamcong.mpi.gov.vn/")
                    .header("User-Agent", "Mozilla/5.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                String responseBody = response.body();
                if (status < 200 || status >= 300) {
                    if (isRetryableStatus(status) && retryAfterDelay(attempt)) {
                        continue;
                    }
                    logFailure(logFailures, context + " failed: HTTP " + status);
                    return null;
                }
                if (!looksLikeJson(responseBody)) {
                    if (retryAfterDelay(attempt)) {
                        continue;
                    }
                    if (logFailures) {
                        String contentType = response.headers().firstValue("content-type").orElse("unknown");
                        logFailure(
                            true,
                            context + " failed: non-JSON response (HTTP " + status
                                + ", content-type " + contentType + ", starts with " + preview(responseBody) + ")"
                        );
                    }
                    return null;
                }
                return mapper.readTree(responseBody);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (!BidSheetApiSyncService.isMonitorStopping()) {
                    logFailure(logFailures, context + " failed: interrupted");
                }
                return null;
            } catch (Exception ex) {
                if (retryAfterDelay(attempt)) {
                    continue;
                }
                logFailure(logFailures, context + " failed: " + ex.getMessage());
                return null;
            }
        }
        return null;
    }

    private static void logFailure(boolean logFailures, String message) {
        if (logFailures) {
            Utils.logPlain(message);
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static boolean retryAfterDelay(int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return false;
        }
        try {
            Thread.sleep(RETRY_DELAY_MILLIS * attempt);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean looksLikeJson(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String preview(String body) {
        if (body == null) {
            return "<empty>";
        }
        String value = body.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) {
            return "<empty>";
        }
        return value.length() <= 80 ? value : value.substring(0, 80) + "...";
    }
}
