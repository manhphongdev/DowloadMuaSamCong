package vn.muasamcong.downloader.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.stream.Stream;

public final class BbmtFileSupport {

    public static final String DEFAULT_NAME_PREFIX = "Biên bản mở thầu";

    private BbmtFileSupport() {
    }

    public static String defaultFileName(String notifyNo) {
        if (notifyNo == null || notifyNo.isBlank()) {
            return DEFAULT_NAME_PREFIX + ".pdf";
        }
        return DEFAULT_NAME_PREFIX + " - " + notifyNo.trim() + ".pdf";
    }

    /**
     * Checks {@code auto-download/} first, then the package folder root (portal sometimes
     * leaves {@code _Biên bản mở thầu.pdf} next to other artifacts).
     */
    public static boolean isPresentForPackage(Path packageFolder, String notifyNo) {
        if (packageFolder == null || !Files.isDirectory(packageFolder)) {
            return false;
        }
        Path autoDownload = packageFolder.resolve("auto-download");
        if (isPresentOnDisk(autoDownload, notifyNo)) {
            return true;
        }
        return scanDir(packageFolder, notifyNo, false);
    }

    public static boolean isPresentOnDisk(Path autoDownloadDir) {
        return isPresentOnDisk(autoDownloadDir, null);
    }

    /** True when a BBMT-like PDF exists, or any PDF whose name contains {@code notifyNo}. */
    public static boolean isPresentOnDisk(Path autoDownloadDir, String notifyNo) {
        if (autoDownloadDir == null || !Files.isDirectory(autoDownloadDir)) {
            return false;
        }
        return scanDir(autoDownloadDir, notifyNo, true);
    }

    private static boolean scanDir(Path dir, String notifyNo, boolean allowGenericNotifyPdf) {
        String notifyToken = normalizeNotifyToken(notifyNo);
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(path -> looksLikeBbmtFileName(path, notifyNo)
                || (allowGenericNotifyPdf && matchesGenericBbmtNotifyPdf(path, notifyToken)));
        } catch (IOException ex) {
            return false;
        }
    }

    private static String normalizeNotifyToken(String notifyNo) {
        if (notifyNo == null || notifyNo.isBlank()) {
            return null;
        }
        return notifyNo.trim().toLowerCase(Locale.ROOT);
    }

    /** Other PDFs in the same folder that contain the notify id but are not BBMT. */
    private static boolean matchesGenericBbmtNotifyPdf(Path file, String notifyToken) {
        if (notifyToken == null || file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pdf") || !lower.contains(notifyToken) || isKnownNonBbmtArtifact(lower)) {
            return false;
        }
        return true;
    }

    public static boolean looksLikeBbmtFileName(Path file) {
        return looksLikeBbmtFileName(file, null);
    }

    public static boolean looksLikeBbmtFileName(Path file, String notifyNo) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String fileName = file.getFileName().toString();
        if (looksLikeBbmtFileLabel(fileName)) {
            return true;
        }
        String notifyToken = normalizeNotifyToken(notifyNo);
        if (notifyToken == null) {
            return false;
        }
        return looksLikeBbmtContractorListExport(fileName, notifyToken);
    }

    /** Portal/Chrome sometimes saves {@code Contractor_List_IB2600009624.pdf} from the BBMT flow. */
    private static boolean looksLikeBbmtContractorListExport(String fileName, String notifyToken) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pdf") || !lower.contains("contractor_list")) {
            return false;
        }
        return lower.contains(notifyToken);
    }

    private static boolean isKnownNonBbmtArtifact(String lowerFileName) {
        return lowerFileName.contains("bcdg")
            || lowerFileName.contains("bang du thau")
            || lowerFileName.contains("hsdt")
            || lowerFileName.contains("quyet dinh")
            || lowerFileName.contains("quyết định")
            || lowerFileName.contains("e-hsdt");
    }

    public static boolean looksLikeBbmtFileLabel(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pdf")) {
            return false;
        }
        String folded = foldForMatch(lower);
        return lower.contains("bbmt")
            || lower.contains("bien ban")
            || lower.contains("biên bản")
            || lower.contains("mo thau")
            || lower.contains("mở thầu")
            || folded.contains("bien ban mo thau")
            || folded.contains("bbmt");
    }

    private static String foldForMatch(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}+", "");
    }
}
