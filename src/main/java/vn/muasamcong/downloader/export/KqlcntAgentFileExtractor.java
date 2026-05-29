package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Maps KQLCNT API fields to agent-downloadable files.
 * <p>
 * Web label for E-HSDT (see {@link vn.muasamcong.downloader.core.DownloadWorker}):
 * "Báo cáo đánh giá tổng hợp E-HSDT" — not bid-package BCDG attachments
 * (e.g. "7. BCDG E-HSDT (đang tải)...").
 */
public final class KqlcntAgentFileExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KqlcntAgentFileExtractor() {
    }

    public static List<AgentFileRef> from(JsonNode kqlcntMain) {
        List<AgentFileRef> refs = new ArrayList<>();
        if (kqlcntMain == null || kqlcntMain.isMissingNode() || kqlcntMain.isNull()) {
            return refs;
        }
        String decisionFileId = text(kqlcntMain, "decisionFileId");
        String decisionFileName = text(kqlcntMain, "decisionFileName");
        if (!isBlank(decisionFileId)) {
            refs.add(new AgentFileRef(decisionFileId, decisionFileName));
        }

        AgentFileRef evalReport = evalReportRef(kqlcntMain);
        if (evalReport != null) {
            refs.add(evalReport);
        }
        AgentFileRef ehsdtReport = ehsdtSummaryReportRef(kqlcntMain);
        if (ehsdtReport != null && !sameFileId(evalReport, ehsdtReport)) {
            refs.add(ehsdtReport);
        }
        return refs;
    }

    public static AgentFileRef decisionRef(JsonNode kqlcntMain) {
        if (kqlcntMain == null) {
            return null;
        }
        String fileId = text(kqlcntMain, "decisionFileId");
        if (isBlank(fileId)) {
            return null;
        }
        return new AgentFileRef(fileId, text(kqlcntMain, "decisionFileName"));
    }

    /** Báo cáo đánh giá chuyên gia — {@code evalReportFileInfo} type {@code EVAL_REPORT}. */
    public static AgentFileRef evalReportRef(JsonNode kqlcntMain) {
        if (kqlcntMain == null) {
            return null;
        }
        AgentFileRef fromEval = firstEvalInfoItem(
            kqlcntMain,
            item -> isEvalReportType(text(item, "type"))
                && !isEhsdtSummaryReportTitle(text(item, "fileName"))
        );
        if (fromEval != null) {
            return fromEval;
        }
        String reportFileId = text(kqlcntMain, "reportFileId");
        String reportFileName = text(kqlcntMain, "reportFileName");
        if (isBlank(reportFileId)
            || isEhsdtSummaryReportTitle(reportFileName)
            || looksLikeBidPackageDoc(reportFileName)) {
            return null;
        }
        if (looksLikeExpertEvalReportTitle(reportFileName)) {
            return new AgentFileRef(reportFileId, reportFileName);
        }
        return null;
    }

    /**
     * Báo cáo đánh giá tổng hợp E-HSDT on the KQLCNT tab (same section as Selenium click target).
     */
    public static AgentFileRef ehsdtSummaryReportRef(JsonNode kqlcntMain) {
        if (kqlcntMain == null) {
            return null;
        }

        AgentFileRef fromArray = firstEvalInfoItem(
            kqlcntMain,
            item -> isEhsdtSummaryReportType(text(item, "type"))
                || isEhsdtSummaryReportTitle(text(item, "fileName"))
        );
        if (fromArray != null) {
            return fromArray;
        }

        String reportFileId = text(kqlcntMain, "reportFileId");
        String reportFileName = text(kqlcntMain, "reportFileName");
        if (isBlank(reportFileId) || !isEhsdtSummaryReportTitle(reportFileName)) {
            return null;
        }
        AgentFileRef eval = evalReportRef(kqlcntMain);
        if (eval != null && reportFileId.equals(eval.fileId())) {
            return null;
        }
        return new AgentFileRef(reportFileId, reportFileName);
    }

    /** @deprecated use {@link #evalReportRef} or {@link #ehsdtSummaryReportRef} */
    public static AgentFileRef reportRef(JsonNode kqlcntMain) {
        return evalReportRef(kqlcntMain);
    }

    /** @deprecated use {@link #ehsdtSummaryReportRef} */
    public static AgentFileRef ehsdtReportRef(JsonNode kqlcntMain) {
        return ehsdtSummaryReportRef(kqlcntMain);
    }

    private static AgentFileRef firstEvalInfoItem(JsonNode kqlcntMain, Predicate<JsonNode> accept) {
        String raw = text(kqlcntMain, "evalReportFileInfo");
        if (isBlank(raw)) {
            return null;
        }
        try {
            JsonNode array = MAPPER.readTree(raw);
            if (array == null || !array.isArray()) {
                return null;
            }
            for (JsonNode item : array) {
                String fileId = text(item, "fileId");
                if (isBlank(fileId) || (accept != null && !accept.test(item))) {
                    continue;
                }
                return new AgentFileRef(fileId, text(item, "fileName"));
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private static boolean isEvalReportType(String type) {
        return type != null && "EVAL_REPORT".equalsIgnoreCase(type.trim());
    }

    private static boolean isEhsdtSummaryReportType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if ("EVAL_REPORT".equals(normalized)) {
            return false;
        }
        return normalized.contains("EHSDT")
            || normalized.contains("E_HSDT")
            || normalized.contains("SUMMARY");
    }

    /**
     * Matches the portal section title, not HSMT/BCDG package filenames.
     */
    static boolean isEhsdtSummaryReportTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        if (looksLikeBidPackageDoc(fileName)) {
            return false;
        }
        String n = normalizeTitle(fileName);
        boolean evalReport = n.contains("bao cao danh gia");
        if (!evalReport) {
            return false;
        }
        boolean tongHopEhsdt = n.contains("tong hop") && n.contains("e-hsdt");
        boolean tongHopEhsdtAlt = n.contains("tong hop e hsdt");
        boolean danhGiaHsdt = n.contains("danh gia hsdt") || n.contains("danh gia e-hsdt");
        return tongHopEhsdt || tongHopEhsdtAlt || danhGiaHsdt;
    }

    private static boolean looksLikeExpertEvalReportTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String n = normalizeTitle(fileName);
        return n.contains("chuyen gia")
            || n.contains("xet thau")
            || n.contains("tcg")
            || (n.contains("bao cao") && n.contains("danh gia") && !n.contains("tong hop"));
    }

  /** BCDG / numbered bid-package docs — not the KQLCNT evaluation summary. */
    static boolean looksLikeBidPackageDoc(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String n = normalizeTitle(fileName);
        if (n.matches("^\\d+\\s*\\.?\\s*bcdg.*")) {
            return true;
        }
        if (n.contains("bcdg e-hsdt") || n.contains("bcđg e-hsdt")) {
            return true;
        }
        return n.contains("mau bcdg")
            || n.contains("bcxt")
            || n.contains("e-hsdt (dang tai)")
            || n.contains("e-hsdt (đang tải)");
    }

    private static String normalizeTitle(String value) {
        String lower = value.toLowerCase(Locale.ROOT).trim();
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static boolean sameFileId(AgentFileRef left, AgentFileRef right) {
        if (left == null || right == null) {
            return false;
        }
        return left.fileId() != null && left.fileId().equals(right.fileId());
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText(null);
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text.trim())) {
            return null;
        }
        return text.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "undefined".equalsIgnoreCase(value.trim());
    }
}
