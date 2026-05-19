package vn.muasamcong.downloader.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import vn.muasamcong.downloader.model.KeywordTarget;

public final class FolderKeywordReader {

    private static final Pattern KEYWORD_PATTERN = Pattern.compile("^\\s*\\d+\\s*\\.\\s*(IB[A-Za-z0-9]+)(?=\\.|\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLDER_ORDER_PATTERN = Pattern.compile("^\\s*(\\d+)\\s*\\.");

    private FolderKeywordReader() {
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

        targets.sort(Comparator
            .comparing(
                FolderKeywordReader::parentFolderName,
                Comparator.nullsLast(String::compareToIgnoreCase)
            )
            .thenComparing(
                FolderKeywordReader::folderOrder,
                Comparator.nullsLast(Integer::compareTo)
            )
            .thenComparing(
                FolderKeywordReader::folderName,
                Comparator.nullsLast(String::compareToIgnoreCase)
            ));
        return targets;
    }

    static String extractKeyword(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return "";
        }

        Matcher matcher = KEYWORD_PATTERN.matcher(folderName);
        if (!matcher.find()) {
            return "";
        }

        return matcher.group(1).toUpperCase();
    }

    private static Integer folderOrder(KeywordTarget target) {
        String folderName = folderName(target);
        if (folderName == null) {
            return null;
        }

        Matcher matcher = FOLDER_ORDER_PATTERN.matcher(folderName);
        if (!matcher.find()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String parentFolderName(KeywordTarget target) {
        if (target == null || target.folderPath() == null || target.folderPath().getParent() == null
            || target.folderPath().getParent().getFileName() == null) {
            return null;
        }
        return target.folderPath().getParent().getFileName().toString();
    }

    private static String folderName(KeywordTarget target) {
        if (target == null || target.folderPath() == null || target.folderPath().getFileName() == null) {
            return null;
        }
        return target.folderPath().getFileName().toString();
    }
}
