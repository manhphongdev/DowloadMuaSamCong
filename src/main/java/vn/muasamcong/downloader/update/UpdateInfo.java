package vn.muasamcong.downloader.update;

import java.util.List;

public record UpdateInfo(
    String version,
    String releaseDate,
    String downloadUrl,
    String fileName,
    List<String> notes
) {
    public UpdateInfo normalized() {
        return new UpdateInfo(
            normalize(version),
            normalize(releaseDate),
            normalize(downloadUrl),
            normalize(fileName),
            notes == null ? List.of() : List.copyOf(notes)
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
