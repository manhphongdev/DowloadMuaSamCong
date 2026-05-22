package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Optional;
import vn.muasamcong.downloader.model.BidApiParams;
import vn.muasamcong.downloader.model.ResolvedBidDetail;
import vn.muasamcong.downloader.util.Utils;

public final class BidSearchApiResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    private static final URI SEARCH_URI = URI.create(
        "https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/smart/search?token=fake"
    );

    private BidSearchApiResolver() {
    }

    public static Optional<ResolvedBidDetail> resolve(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Optional.empty();
        }
        JsonNode root = postSearch(keyword.trim());
        JsonNode match = firstMatchingResult(root, keyword.trim());
        if (match == null) {
            return Optional.empty();
        }

        BidApiParams params = new BidApiParams(
            text(match, "notifyNo"),
            firstText(match, "id", "notifyId"),
            firstText(match, "notifyId", "id"),
            text(match, "inputResultId"),
            text(match, "bidOpenId"),
            text(match, "techReqId"),
            text(match, "bidPreNotifyResultId"),
            text(match, "bidPreOpenId"),
            text(match, "processApply"),
            text(match, "bidMode"),
            text(match, "bidForm"),
            text(match, "planNo"),
            firstText(match, "stepCode", "step"),
            text(match, "isInternet")
        );
        if (isBlank(params.notifyId()) || isBlank(params.notifyNo())) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedBidDetail(buildDetailUrl(params), params));
    }

    private static JsonNode postSearch(String keyword) {
        try {
            String escaped = MAPPER.writeValueAsString(keyword);
            String body = "[{\"pageSize\":1,\"pageNumber\":0,\"query\":[{"
                + "\"index\":\"es-contractor-selection\","
                + "\"keyWord\":" + escaped + ","
                + "\"matchType\":\"exact\","
                + "\"matchFields\":[\"notifyNo\",\"bidName\"],"
                + "\"filters\":[{\"fieldName\":\"type\",\"searchType\":\"in\","
                + "\"fieldValues\":[\"es-notify-contractor\"]}]"
                + "}]}]";
            return ApiJsonHttpClient.postJson(
                HTTP_CLIENT,
                MAPPER,
                SEARCH_URI,
                body,
                Duration.ofSeconds(25),
                "Search API for " + keyword
            );
        } catch (Exception ex) {
            Utils.logPlain("Search API failed for " + keyword + ": " + ex.getMessage());
            return null;
        }
    }

    private static JsonNode firstMatchingResult(JsonNode root, String keyword) {
        if (root == null) {
            return null;
        }
        ArrayDeque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        JsonNode fallback = null;
        while (!queue.isEmpty()) {
            JsonNode node = queue.removeFirst();
            if (node.isObject()) {
                String notifyNo = text(node, "notifyNo");
                if (notifyNo != null && notifyNo.equalsIgnoreCase(keyword)) {
                    return node;
                }
                if (fallback == null && notifyNo != null && !isBlank(firstText(node, "id", "notifyId"))) {
                    fallback = node;
                }
                Iterator<JsonNode> values = node.elements();
                while (values.hasNext()) {
                    queue.add(values.next());
                }
            } else if (node.isArray()) {
                for (JsonNode child : node) {
                    queue.add(child);
                }
            }
        }
        return fallback;
    }

    private static String buildDetailUrl(BidApiParams params) {
        StringBuilder sb = new StringBuilder("https://muasamcong.mpi.gov.vn/web/guest/contractor-selection");
        append(sb, "p_p_id", "egpportalcontractorselectionv2_WAR_egpportalcontractorselectionv2", true);
        append(sb, "p_p_lifecycle", "0", false);
        append(sb, "p_p_state", "normal", false);
        append(sb, "p_p_mode", "view", false);
        append(sb, "_egpportalcontractorselectionv2_WAR_egpportalcontractorselectionv2_render", "detail-v2", false);
        append(sb, "type", "es-notify-contractor", false);
        append(sb, "stepCode", params.stepCode(), false);
        append(sb, "id", params.id(), false);
        append(sb, "notifyId", params.notifyId(), false);
        append(sb, "inputResultId", params.inputResultId(), false);
        append(sb, "bidOpenId", params.bidOpenId(), false);
        append(sb, "techReqId", params.techReqId(), false);
        append(sb, "bidPreNotifyResultId", params.bidPreNotifyResultId(), false);
        append(sb, "bidPreOpenId", params.bidPreOpenId(), false);
        append(sb, "processApply", params.processApply(), false);
        append(sb, "bidMode", params.bidMode(), false);
        append(sb, "notifyNo", params.notifyNo(), false);
        append(sb, "planNo", params.planNo(), false);
        append(sb, "pno", "undefined", false);
        append(sb, "step", "tbmt", false);
        append(sb, "isInternet", params.isInternet(), false);
        append(sb, "caseKHKQ", "undefined", false);
        append(sb, "bidForm", params.bidForm(), false);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, String value, boolean first) {
        sb.append(first ? '?' : '&')
            .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
            .append('=')
            .append(URLEncoder.encode(value == null || value.isBlank() ? "undefined" : value, StandardCharsets.UTF_8));
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() || "undefined".equalsIgnoreCase(text.trim()) ? null : text.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
