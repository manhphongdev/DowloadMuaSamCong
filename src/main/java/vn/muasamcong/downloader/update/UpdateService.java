package vn.muasamcong.downloader.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import vn.muasamcong.downloader.util.Utils;

public final class UpdateService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final Path UPDATE_DIR = Utils.dataDirectory().resolve("updates").toAbsolutePath().normalize();

    private UpdateService() {
    }

    public static UpdateInfo fetchLatest(String metadataUrl) throws Exception {
        if (metadataUrl == null || metadataUrl.isBlank()) {
            throw new IllegalArgumentException("Update metadata URL is required.");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(metadataUrl.trim()))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response.statusCode(), response.body(), "fetch update metadata");

        UpdateInfo info = OBJECT_MAPPER.readValue(response.body(), UpdateInfo.class).normalized();
        if (info.version() == null || info.version().isBlank()) {
            throw new IllegalArgumentException("Update metadata is missing version.");
        }
        if (info.downloadUrl() == null || info.downloadUrl().isBlank()) {
            throw new IllegalArgumentException("Update metadata is missing downloadUrl.");
        }
        return info;
    }

    public static boolean isNewerVersion(String latestVersion, String currentVersion) {
        return compareVersions(latestVersion, currentVersion) > 0;
    }

    public static Path downloadUpdate(UpdateInfo info) throws Exception {
        return downloadUpdate(info, null);
    }

    public static Path downloadUpdate(UpdateInfo info, BiConsumer<Long, Long> progressListener) throws Exception {
        if (info == null || info.downloadUrl() == null || info.downloadUrl().isBlank()) {
            throw new IllegalArgumentException("Download URL is required.");
        }

        Utils.ensureDirectory(UPDATE_DIR);
        String fileName = resolveFileName(info);
        Path target = UPDATE_DIR.resolve(fileName).toAbsolutePath().normalize();
        Path temp = UPDATE_DIR.resolve(fileName + ".download").toAbsolutePath().normalize();

        HttpRequest request = HttpRequest.newBuilder(URI.create(info.downloadUrl()))
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build();
        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        ensureOk(response.statusCode(), "", "download update");
        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

        try (InputStream in = response.body()) {
            byte[] buffer = new byte[64 * 1024];
            long downloaded = 0L;
            try (java.io.OutputStream out = Files.newOutputStream(temp)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (progressListener != null) {
                        progressListener.accept(downloaded, totalBytes);
                    }
                }
            }
        }
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public static Path updateDirectory() {
        return UPDATE_DIR;
    }

    private static String resolveFileName(UpdateInfo info) {
        if (info.fileName() != null && !info.fileName().isBlank()) {
            return sanitizeFileName(info.fileName());
        }

        try {
            String path = URI.create(info.downloadUrl()).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (name != null && !name.isBlank()) {
                return sanitizeFileName(name);
            }
        } catch (Exception ignored) {
            // fallback below
        }

        return "MuaSamCong-" + sanitizeFileName(info.version()) + ".zip";
    }

    private static String sanitizeFileName(String value) {
        String safe = value == null ? "update" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isBlank() ? "update" : safe;
    }

    private static int compareVersions(String left, String right) {
        List<Integer> a = parseVersion(left);
        List<Integer> b = parseVersion(right);
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            int ai = i < a.size() ? a.get(i) : 0;
            int bi = i < b.size() ? b.get(i) : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    private static List<Integer> parseVersion(String version) {
        String normalized = version == null ? "" : version.trim().replaceFirst("^[vV]", "");
        String[] parts = normalized.split("[.-]");
        List<Integer> result = new ArrayList<>();
        for (String part : parts) {
            String digits = part.replaceAll("[^0-9]", "");
            if (!digits.isBlank()) {
                try {
                    result.add(Integer.parseInt(digits));
                } catch (NumberFormatException ignored) {
                    result.add(0);
                }
            }
        }
        return result.isEmpty() ? List.of(0) : result;
    }

    private static void ensureOk(int statusCode, String body, String action) throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw new IOException("Unable to " + action + " (HTTP " + statusCode + "): " + (body == null ? "" : body));
    }
}
