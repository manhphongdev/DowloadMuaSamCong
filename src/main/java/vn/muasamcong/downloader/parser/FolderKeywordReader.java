package vn.muasamcong.downloader.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import vn.muasamcong.downloader.model.KeywordTarget;

public final class FolderKeywordReader {

    private FolderKeywordReader() {
    }

    public static List<KeywordTarget> readKeywords(Path rootFolder) {
        return readKeywords(List.of(rootFolder));
    }

    public static List<KeywordTarget> readKeywords(List<Path> rootFolders) {
        if (rootFolders == null || rootFolders.isEmpty()) {
            throw new IllegalArgumentException("Root folder list is empty.");
        }

        List<KeywordTarget> targets = new ArrayList<>();
        for (Path rootFolder : rootFolders) {
            if (rootFolder == null) {
                continue;
            }

            if (!Files.exists(rootFolder)) {
                throw new IllegalArgumentException("Folder not found: " + rootFolder.toAbsolutePath());
            }
            if (!Files.isDirectory(rootFolder)) {
                throw new IllegalArgumentException("Path is not a folder: " + rootFolder.toAbsolutePath());
            }

            try (Stream<Path> children = Files.list(rootFolder)) {
                children
                    .filter(Files::isDirectory)
                    .forEach(folderPath -> {
                        String keyword = extractKeyword(folderPath.getFileName().toString());
                        if (!keyword.isEmpty()) {
                            targets.add(new KeywordTarget(keyword, folderPath));
                        }
                    });
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read subfolders from: " + rootFolder.toAbsolutePath(), ex);
            }
        }

        return targets;
    }

    static String extractKeyword(String folderName) {
        int firstDot = folderName.indexOf('.');
        if (firstDot < 0) {
            return "";
        }

        int secondDot = folderName.indexOf('.', firstDot + 1);
        if (secondDot < 0 || secondDot <= firstDot + 1) {
            return "";
        }

        String rawKeyword = folderName.substring(firstDot + 1, secondDot);
        return rawKeyword.replaceAll("\\s+", "").trim();
    }
}
