package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import vn.muasamcong.downloader.model.BidApiParams;
import vn.muasamcong.downloader.model.DownloadHints;
import vn.muasamcong.downloader.util.Utils;

public final class MonitorApiChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    private static final URI KQLCNT_URI = URI.create(
        "https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/expose/contractor-input-result/get?token=fake"
    );

    private MonitorApiChecker() {
    }

    public static DownloadHints check(BidApiParams params) {
        if (params == null) {
            return DownloadHints.fullRun();
        }

        boolean needBbmt = params.bidOpenId() != null && !params.bidOpenId().isBlank();

        if (isBlank(params.inputResultId())) {
            return new DownloadHints(false, needBbmt, false, false, false, false);
        }

        JsonNode kqlcnt = fetchKqlcnt(params.inputResultId());
        if (kqlcnt == null) {
            Utils.logPlain("MonitorApiChecker: KQLCNT API failed for inputResultId="
                + params.inputResultId() + ", falling back to full run.");
            return new DownloadHints(false, needBbmt, true, true, true, true);
        }

        JsonNode dto = kqlcnt.path("bideContractorInputResultDTO");
        if (dto.isMissingNode() || dto.isNull()) {
            return new DownloadHints(false, needBbmt, false, false, false, false);
        }

        boolean needKqlcntDecision = !isBlankNode(dto.get("decisionFileId"));
        boolean needKqlcntReport   = !isBlankNode(dto.get("reportFileId"));
        boolean needGoodsExcel     = hasGoodsList(dto);
        boolean needContractorCsv  = hasWinningContractor(dto);

        return new DownloadHints(false, needBbmt, needKqlcntDecision, needKqlcntReport, needGoodsExcel, needContractorCsv);
    }

    private static JsonNode fetchKqlcnt(String inputResultId) {
        try {
            String body = "{\"id\":" + MAPPER.writeValueAsString(inputResultId) + "}";
            return ApiJsonHttpClient.postJson(
                HTTP_CLIENT,
                MAPPER,
                KQLCNT_URI,
                body,
                Duration.ofSeconds(25),
                "MonitorApiChecker KQLCNT API inputResultId=" + inputResultId
            );
        } catch (Exception ex) {
            Utils.logPlain("MonitorApiChecker KQLCNT API failed for inputResultId=" + inputResultId + ": " + ex.getMessage());
            return null;
        }
    }

    private static boolean hasGoodsList(JsonNode dto) {
        JsonNode lots = dto.get("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return false;
        }
        for (JsonNode lot : lots) {
            JsonNode goodsList = lot.get("goodsList");
            if (goodsList != null && !goodsList.isNull()) {
                String raw = goodsList.asText();
                if (raw != null && !raw.isBlank() && !raw.equals("null")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasWinningContractor(JsonNode dto) {
        JsonNode lots = dto.get("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return false;
        }
        for (JsonNode lot : lots) {
            JsonNode contractors = lot.get("contractorList");
            if (contractors == null || !contractors.isArray()) {
                continue;
            }
            for (JsonNode contractor : contractors) {
                if (contractor.path("bidResult").asInt(-1) == 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBlankNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        String text = node.asText();
        return text == null || text.isBlank() || text.equals("null");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
