package vn.muasamcong.downloader.application.monitor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.muasamcong.downloader.model.DownloadHints;
import vn.muasamcong.downloader.model.KeywordTarget;

public record PackageDownloadPlan(
    List<KeywordTarget> targets,
    Map<String, DownloadHints> hintsByTargetKey,
    Map<String, String> detailUrlByTargetKey,
    int skippedCount,
    List<String> skipLogLines
) {

    public PackageDownloadPlan {
        targets = targets == null ? List.of() : List.copyOf(targets);
        hintsByTargetKey = hintsByTargetKey == null ? Map.of() : Map.copyOf(hintsByTargetKey);
        detailUrlByTargetKey = detailUrlByTargetKey == null ? Map.of() : Map.copyOf(detailUrlByTargetKey);
        skipLogLines = skipLogLines == null ? List.of() : List.copyOf(skipLogLines);
    }

    public static PackageDownloadPlan empty(int skippedCount, List<String> skipLogLines) {
        return new PackageDownloadPlan(List.of(), Map.of(), Map.of(), skippedCount, skipLogLines);
    }
}
