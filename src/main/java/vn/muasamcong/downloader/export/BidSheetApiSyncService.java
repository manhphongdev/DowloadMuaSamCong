package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import vn.muasamcong.downloader.application.sheet.SheetRefreshResult;
import vn.muasamcong.downloader.core.AppFeatures;
import vn.muasamcong.downloader.core.DownloadWorker;
import vn.muasamcong.downloader.model.ArtifactFingerprints;
import vn.muasamcong.downloader.model.BidApiParams;
import vn.muasamcong.downloader.model.DownloadHints;
import vn.muasamcong.downloader.model.BidSheetRow;
import vn.muasamcong.downloader.model.BidSheetRowBuilder;
import vn.muasamcong.downloader.model.BidStatus;
import vn.muasamcong.downloader.model.BidTrackingRecord;
import vn.muasamcong.downloader.store.ArtifactFingerprintStore;
import vn.muasamcong.downloader.store.BidTrackingRecordStore;
import vn.muasamcong.downloader.util.Utils;

public final class BidSheetApiSyncService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile boolean agentExportEnabled = true;
    private static volatile boolean bbmtSeleniumPreferred = false;
    private static volatile boolean monitorStopping = false;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();
    private static final String BASE_API =
        "https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services";
    private static final URI TBMT_URI = URI.create(BASE_API + "/lcnt_tbmt_ttc_ldt?token=fake");
    private static final URI KQLCNT_URI = URI.create(BASE_API + "/expose/contractor-input-result/get?token=fake");
    private static final URI CONTRACT_INFO_URI = URI.create(
        BASE_API + "/econsign/contract-info/list-contract-for-po?token=fake"
    );
    private static final URI HSMT_CLARIFICATION_URI = URI.create(BASE_API + "/lcnt_tbmt_yclr?token=fake");
    private static final URI PETITION_URI = URI.create(BASE_API + "/lcnt_tbmt_kn?token=fake");
    private static final URI LOT_OPEN_URI = URI.create(
        BASE_API + "/expose/ldtkqmt/bid-notification-p/lot-open?token=fake"
    );
    private static final URI BID_OPEN_URI = URI.create(
        BASE_API + "/expose/ldtkqmt/bid-notification-p/bid-open?token=fake"
    );
    private static final URI LOT_OPEN_DETAIL_URI = URI.create(
        BASE_API + "/expose/ldtkqmt/bid-notification-p/lotOpenDetail?token=fake"
    );

    private BidSheetApiSyncService() {
    }

    public static void setAgentExportEnabled(boolean enabled) {
        agentExportEnabled = enabled;
    }

    public static void setBbmtSeleniumPreferred(boolean preferred) {
        bbmtSeleniumPreferred = preferred;
    }

    public static void setMonitorStopping(boolean stopping) {
        monitorStopping = stopping;
    }

    static boolean isMonitorStopping() {
        return monitorStopping;
    }

    private static boolean shouldFetchBbmtOpenApis() {
        return AppFeatures.isMonitorAgentFileDownloadEnabled()
            && agentExportEnabled;
    }

    public record ApiBundle(
        JsonNode searchResult,
        JsonNode tbmt,
        JsonNode kqlcnt,
        JsonNode contractInfo,
        JsonNode clarification,
        JsonNode petition,
        JsonNode lotOpen,
        JsonNode bidOpen,
        JsonNode lotOpenDetail
    ) {
        public ApiBundle(JsonNode searchResult, JsonNode tbmt, JsonNode kqlcnt, JsonNode contractInfo) {
            this(searchResult, tbmt, kqlcnt, contractInfo, null, null, null, null, null);
        }
    }

    public record PackageApiSyncResult(
        BidSheetRow row,
        DownloadHints hints,
        String sheetRowHash,
        String contractorCsvHash,
        String goodsExcelHash
    ) {
    }

    public static ApiBundle fetchApiBundle(BidApiParams params, String keyword) {
        if (params == null) {
            return new ApiBundle(null, null, null, null, null, null, null, null, null);
        }
        if (Thread.currentThread().isInterrupted()) {
            return new ApiBundle(null, null, null, null, null, null, null, null, null);
        }
        String notifyNo = firstNonBlank(params.notifyNo(), keyword);
        String processApply = firstNonBlank(params.processApply(), "LDT");
        JsonNode searchResult = BidSearchApiResolver.resolveMatch(notifyNo);
        JsonNode tbmt = postJson(TBMT_URI, "{\"id\":" + quote(params.notifyId()) + "}");
        if (Thread.currentThread().isInterrupted()) {
            return new ApiBundle(searchResult, tbmt, null, null, null, null, null, null, null);
        }
        JsonNode kqlcnt = isBlankOrUndefined(params.inputResultId())
            ? null
            : postJson(KQLCNT_URI, "{\"id\":" + quote(params.inputResultId()) + "}");
        if (Thread.currentThread().isInterrupted()) {
            return new ApiBundle(searchResult, tbmt, kqlcnt, null, null, null, null, null, null);
        }
        if (Thread.currentThread().isInterrupted()) {
            return new ApiBundle(searchResult, tbmt, kqlcnt, null, null, null, null, null, null);
        }
        JsonNode contractInfo = isBlankOrUndefined(notifyNo)
            ? null
            : postJson(CONTRACT_INFO_URI, "{\"notifyNo\":" + quote(notifyNo) + "}");
        if (Thread.currentThread().isInterrupted()) {
            return new ApiBundle(searchResult, tbmt, kqlcnt, contractInfo, null, null, null, null, null);
        }
        JsonNode clarification = isBlankOrUndefined(notifyNo)
            ? null
            : postJson(
                HSMT_CLARIFICATION_URI,
                "{\"notifyNo\":" + quote(notifyNo) + ",\"processApply\":" + quote(processApply) + "}"
            );
        if (Thread.currentThread().isInterrupted()) {
            return new ApiBundle(searchResult, tbmt, kqlcnt, contractInfo, clarification, null, null, null, null);
        }
        JsonNode petition = isBlankOrUndefined(notifyNo)
            ? null
            : postJson(
                PETITION_URI,
                "{\"notifyNo\":" + quote(notifyNo) + ",\"processApply\":" + quote(processApply) + "}"
            );
        if (Thread.currentThread().isInterrupted()) {
            return new ApiBundle(searchResult, tbmt, kqlcnt, contractInfo, clarification, petition, null, null, null);
        }
        JsonNode lotOpen = null;
        JsonNode bidOpen = null;
        JsonNode lotOpenDetail = null;
        if (shouldFetchBbmtOpenApis()
            && !isBlankOrUndefined(notifyNo)
            && !isBlankOrUndefined(params.notifyId())
            && !isBlankOrUndefined(params.bidOpenId())) {
            int packType = resolveBbmtPackType(tbmt, searchResult);
            int viewType = resolveBbmtViewType(tbmt, searchResult);
            String lotBaseBody = "{\"notifyNo\":" + quote(notifyNo)
                + ",\"type\":\"TBMT\",\"packType\":" + packType
                + ",\"viewType\":" + viewType
                + ",\"notifyId\":" + quote(params.notifyId())
                + ",\"bidOpenId\":" + quote(params.bidOpenId()) + "}";
            lotOpen = postJsonOptional(LOT_OPEN_URI, lotBaseBody);
            lotOpenDetail = postJsonOptional(LOT_OPEN_DETAIL_URI, lotBaseBody);
            bidOpen = postJsonOptional(
                BID_OPEN_URI,
                "{\"notifyId\":" + quote(params.notifyId())
                    + ",\"bidOpenId\":" + quote(params.bidOpenId())
                    + ",\"id\":" + quote(params.notifyId()) + "}"
            );
        }
        return new ApiBundle(
            searchResult,
            tbmt,
            kqlcnt,
            contractInfo,
            clarification,
            petition,
            lotOpen,
            bidOpen,
            lotOpenDetail
        );
    }

    public static PackageApiSyncResult syncPackage(BidTrackingRecord record) {
        if (record == null || record.apiParams() == null) {
            return null;
        }
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        ApiBundle bundle = fetchApiBundle(record.apiParams(), record.keyword());
        BidSheetRow row = buildRowWithBundle(record, loadExistingStatuses(), bundle);
        DownloadHints hints = resolveDownloadHints(record.apiParams(), bundle, record.folderPath());
        String sheetHash = row == null ? null : sheetRowHash(row);
        ArtifactFingerprints fingerprints = ArtifactFingerprintStore.find(record.key());
        return new PackageApiSyncResult(
            row,
            hints,
            sheetHash,
            fingerprints == null ? null : fingerprints.contractorCsvHash(),
            fingerprints == null ? null : fingerprints.goodsExcelHash()
        );
    }

    public static SheetRefreshResult refreshBidSheetRowsFromTracking() {
        List<BidTrackingRecord> records = BidTrackingRecordStore.loadRecords();
        Map<String, BidStatus> existingStatuses = loadExistingStatuses();
        Set<String> existingRowKeys = loadExistingRowKeys();
        Set<String> newRowKeys = new LinkedHashSet<>();
        List<BidSheetRow> rows = new ArrayList<>();
        List<String> sheetDataChangedKeys = new ArrayList<>();
        for (BidTrackingRecord record : records) {
            if (Thread.currentThread().isInterrupted()) {
                Utils.logPlain("Bid sheet refresh interrupted. Stopping remaining API requests.");
                return new SheetRefreshResult(loadExistingRowKeys().size(), List.of(), false, true);
            }
            if (record == null || record.key() == null || record.key().isBlank()) {
                continue;
            }
            String beforeHash = sheetRowHashBeforeRefresh(record.key());
            BidSheetRow row = buildRow(record, existingStatuses);
            if (row != null) {
                rows.add(row);
                newRowKeys.add(rowKey(row.tmbtNumber(), row.parentFolderName(), row.contractPerformanceFolder()));
                if (sheetRowHashChanged(record.key(), beforeHash)) {
                    sheetDataChangedKeys.add(record.key());
                }
            }
        }
        boolean rowSetChanged = !existingRowKeys.equals(newRowKeys);
        boolean sheetChanged = rowSetChanged || !sheetDataChangedKeys.isEmpty();
        if (sheetChanged) {
            DownloadWorker.replaceBidInfoRows(rows);
        } else {
            Utils.logPlain("Bid sheet rows unchanged. Skipping bid_sheet_rows.json write.");
        }
        return new SheetRefreshResult(rows.size(), List.copyOf(sheetDataChangedKeys), sheetChanged, false);
    }

    private static Set<String> loadExistingRowKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Path jsonFile = Utils.dataFile("bid_sheet_rows.json");
        if (!Files.exists(jsonFile)) {
            return keys;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(jsonFile));
            JsonNode rows = root == null ? null : (root.isArray() ? root : root.path("rows"));
            if (rows == null || !rows.isArray()) {
                return keys;
            }
            for (JsonNode row : rows) {
                keys.add(rowKey(
                    text(row, "tmbtNumber"),
                    text(row, "parentFolderName"),
                    text(row, "contractPerformanceFolder")
                ));
            }
        } catch (Exception ex) {
            Utils.logPlain("Unable to load existing bid sheet row keys: " + ex.getMessage());
        }
        return keys;
    }

    private static String sheetRowHashBeforeRefresh(String key) {
        ArtifactFingerprints existing = ArtifactFingerprintStore.find(key);
        return existing == null ? null : existing.sheetRowHash();
    }

    private static boolean sheetRowHashChanged(String key, String beforeHash) {
        ArtifactFingerprints after = ArtifactFingerprintStore.find(key);
        String afterHash = after == null ? null : after.sheetRowHash();
        if (afterHash == null) {
            return false;
        }
        return beforeHash == null || !beforeHash.equals(afterHash);
    }

    public static DownloadHints resolveDownloadHints(BidApiParams params) {
        if (params == null) {
            return DownloadHints.unknown();
        }
        return resolveDownloadHints(params, fetchApiBundle(params, params.notifyNo()), null);
    }

    public static DownloadHints resolveDownloadHints(BidApiParams params, ApiBundle bundle) {
        return resolveDownloadHints(params, bundle, null);
    }

    public static DownloadHints resolveDownloadHints(BidApiParams params, ApiBundle bundle, String folderPath) {
        if (params == null) {
            return DownloadHints.unknown();
        }
        ApiBundle resolved = bundle == null ? fetchApiBundle(params, params.notifyNo()) : bundle;
        JsonNode searchResult = resolved.searchResult();
        JsonNode tbmt = resolved.tbmt();
        JsonNode kqlcnt = resolved.kqlcnt();
        JsonNode kqlcntMain = kqlcnt == null ? null : kqlcnt.path("bideContractorInputResultDTO");
        AgentFileRef decisionRef = KqlcntAgentFileExtractor.decisionRef(kqlcntMain);
        AgentFileRef evalReportRef = KqlcntAgentFileExtractor.evalReportRef(kqlcntMain);
        AgentFileRef ehsdtReportRef = KqlcntAgentFileExtractor.ehsdtSummaryReportRef(kqlcntMain);
        List<AgentFileRef> kqlcntRefs = KqlcntAgentFileExtractor.from(kqlcntMain);
        String decisionFileId = decisionRef == null ? null : decisionRef.fileId();
        String decisionFileName = decisionRef == null ? null : decisionRef.fileName();
        String reportFileId = text(kqlcntMain, "reportFileId");
        String reportFileName = text(kqlcntMain, "reportFileName");
        String goodFileId = text(kqlcntMain, "goodFileId");
        String goodFileName = text(kqlcntMain, "goodFileName");

        boolean hasGoodsData = hasGoodsListData(kqlcntMain);
        boolean hasWinningContractor = hasWinningContractor(kqlcntMain);
        Boolean searchHasClarification = positiveCount(searchResult, "numClarifyReq");
        Boolean searchHasPetition = anyPositiveCount(
            searchResult,
            "numPetition",
            "numPetitionHsmt",
            "numPetitionLcnt",
            "numPetitionKqlcnt"
        );
        JsonNode clarification = resolved.clarification();
        if (clarification == null && searchHasClarification == null) {
            clarification = postJson(
                HSMT_CLARIFICATION_URI,
                "{\"notifyNo\":" + quote(params.notifyNo()) + ",\"processApply\":" + quote(params.processApply()) + "}"
            );
        }
        JsonNode petition = resolved.petition();
        if (petition == null && searchHasPetition == null) {
            petition = postJson(
                PETITION_URI,
                "{\"notifyNo\":" + quote(params.notifyNo()) + ",\"processApply\":" + quote(params.processApply()) + "}"
            );
        }
        boolean hasClarificationContent = searchHasClarification != null
            ? searchHasClarification
            : hasClarificationContent(clarification);
        boolean hasPetitionContent = searchHasPetition != null
            ? searchHasPetition
            : hasPetitionContent(petition);

        Path packageDir = path(folderPath);
        Path autoDownloadDir = autoDownloadFolder(folderPath);
        List<AgentFileRef> clarificationRefs = TbmtAgentFileExtractor.clarificationRefs(clarification);
        List<AgentFileRef> petitionRefs = TbmtAgentFileExtractor.petitionRefs(petition);
        boolean needDecision = !isBlankOrUndefined(decisionFileId)
            && agentRefMissing(autoDownloadDir, decisionRef);
        boolean needEhsdtSummaryReport = agentRefMissing(autoDownloadDir, ehsdtReportRef);
        boolean needEvalReport = agentRefMissing(autoDownloadDir, evalReportRef)
            || anyKqlcntReportRefMissing(autoDownloadDir, kqlcntRefs, decisionFileId);
        boolean needBbmt = bbmtSeleniumPreferred
            && !isBlankOrUndefined(params.bidOpenId())
            && !BbmtFileSupport.isPresentForPackage(packageDir, params.notifyNo());
        boolean needClarification = hasClarificationContent
            && !clarificationRefs.isEmpty()
            && EgpAgentFileDownloadService.anyRefMissingOnDisk(autoDownloadDir, clarificationRefs);
        boolean needPetition = hasPetitionContent
            && !petitionRefs.isEmpty()
            && EgpAgentFileDownloadService.anyRefMissingOnDisk(autoDownloadDir, petitionRefs);

        return new DownloadHints(
            true,
            needClarification || needPetition,
            needClarification,
            needPetition,
            needBbmt,
            needDecision,
            needEhsdtSummaryReport || needEvalReport,
            !isBlankOrUndefined(goodFileId) || hasGoodsData,
            hasWinningContractor,
            decisionFileId,
            decisionFileName,
            reportFileId,
            reportFileName,
            goodFileId,
            goodFileName
        );
    }

    private static boolean anyKqlcntReportRefMissing(
        Path autoDownloadDir,
        List<AgentFileRef> refs,
        String decisionFileId
    ) {
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        List<AgentFileRef> reportRefs = new ArrayList<>();
        for (AgentFileRef ref : refs) {
            if (ref == null || isBlankOrUndefined(ref.fileId())) {
                continue;
            }
            if (decisionFileId != null && decisionFileId.equals(ref.fileId())) {
                continue;
            }
            reportRefs.add(ref);
        }
        return !reportRefs.isEmpty() && EgpAgentFileDownloadService.anyRefMissingOnDisk(autoDownloadDir, reportRefs);
    }

    private static boolean hasGoodsListData(JsonNode kqlcntMain) {
        JsonNode lots = kqlcntMain == null ? null : kqlcntMain.path("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return false;
        }
        for (JsonNode lot : lots) {
            String raw = text(lot, "goodsList");
            if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
                continue;
            }
            try {
                JsonNode goods = MAPPER.readTree(raw);
                if (goods != null && goods.isArray() && goods.size() > 0) {
                    return true;
                }
            } catch (Exception ex) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasApiContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return node.size() > 0;
        }
        if (node.isObject()) {
            JsonNode content = firstExisting(node, "content", "data", "records", "list", "items", "result");
            if (content != null && content != node) {
                return hasApiContent(content);
            }
            JsonNode total = firstExisting(node, "total", "totalElements", "count", "Count");
            if (total != null && total.canConvertToInt() && total.asInt() > 0) {
                return true;
            }
            return node.size() > 0 && !node.toString().equals("{}");
        }
        String value = node.asText();
        return value != null
            && !value.isBlank()
            && !"[]".equals(value.trim())
            && !"{}".equals(value.trim())
            && !"null".equalsIgnoreCase(value.trim());
    }

    private static boolean hasPetitionContent(JsonNode node) {
        JsonNode list = node == null ? null : node.get("biduPetitionContractorVersionDTOList");
        return list != null && list.isArray() && list.size() > 0;
    }

    private static boolean hasClarificationContent(JsonNode node) {
        JsonNode list = node == null ? null : node.get("biduClarifyReqInvAndContentViewVersionDTOList");
        return list != null && list.isArray() && list.size() > 0;
    }

    private static JsonNode firstExisting(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static Boolean anyPositiveCount(JsonNode node, String... fields) {
        if (fields == null || fields.length == 0) {
            return null;
        }
        boolean found = false;
        for (String field : fields) {
            Boolean value = positiveCount(node, field);
            if (value == null) {
                continue;
            }
            found = true;
            if (value) {
                return true;
            }
        }
        return found ? false : null;
    }

    private static Boolean positiveCount(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value != null && value.isArray()) {
            value = value.size() == 0 ? null : value.get(0);
        }
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isNumber()) {
                return value.asInt() > 0;
            }
            String text = value.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            return Integer.parseInt(text.trim()) > 0;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean hasWinningContractor(JsonNode kqlcntMain) {
        JsonNode lots = kqlcntMain == null ? null : kqlcntMain.path("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return false;
        }
        for (JsonNode lot : lots) {
            JsonNode contractors = lot.path("contractorList");
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

    private static BidSheetRow buildRow(
        BidTrackingRecord record,
        Map<String, BidStatus> existingStatuses
    ) {
        if (record == null || record.apiParams() == null) {
            return null;
        }
        return buildRowWithBundle(record, existingStatuses, fetchApiBundle(record.apiParams(), record.keyword()));
    }

    private static BidSheetRow buildRowWithBundle(
        BidTrackingRecord record,
        Map<String, BidStatus> existingStatuses,
        ApiBundle bundle
    ) {
        if (record == null || record.apiParams() == null) {
            return null;
        }
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        BidApiParams params = record.apiParams();
        ApiBundle resolved = bundle == null ? fetchApiBundle(params, record.keyword()) : bundle;
        JsonNode searchResult = resolved.searchResult();
        JsonNode tbmt = resolved.tbmt();
        JsonNode kqlcnt = resolved.kqlcnt();

        JsonNode tbmtMain = tbmt == null ? null : tbmt.path("bidoNotifyContractorM");
        JsonNode kqlcntMain = kqlcnt == null ? null : kqlcnt.path("bideContractorInputResultDTO");
        LocalDateTime bidClosingTime = firstNonNull(
            parseDateTime(text(searchResult, "bidCloseDate")),
            parseDateTime(text(tbmtMain, "bidCloseDate"))
        );
        LocalDateTime bidOpenTime = firstNonNull(
            parseDateTime(text(searchResult, "bidOpenDate")),
            parseDateTime(text(tbmtMain, "bidOpenDate"))
        );
        exportContractorReport(record, kqlcntMain);
        exportGoodsBidExcel(record, kqlcntMain);
        exportAgentFiles(record, params, resolved);

        String folderPath = record.folderPath();
        String contractFolderName = folderName(folderPath);
        String parentFolderName = parentFolderName(folderPath);
        String packageExecutionTime = winningContractPeriodText(kqlcntMain);
        BigInteger winningBidPrice = winningBidPrice(kqlcntMain);
        String tmbtNumber = firstNonBlank(
            text(searchResult, "notifyNo"),
            text(tbmtMain, "notifyNo"),
            text(kqlcntMain, "notifyNo"),
            params.notifyNo()
        );
        String packageName = firstNonBlank(
            text(searchResult, "bidName"),
            text(tbmtMain, "bidName"),
            text(kqlcntMain, "bidName")
        );
        String procuringEntity = firstNonBlank(
            text(searchResult, "investorName"),
            text(tbmtMain, "investorName"),
            text(kqlcntMain, "investorName"),
            text(tbmtMain, "procuringEntityName")
        );
        BigInteger estimatedBudget = firstNonNull(
            bigInteger(searchResult, "bidPrice"),
            bigInteger(tbmtMain, "bidEstimatePrice"),
            bigInteger(kqlcntMain, "bidEstimatePrice")
        );
        LocalDateTime postedDate = firstNonNull(
            parseDateTime(text(searchResult, "publicDate")),
            parseDateTime(text(tbmtMain, "publicDate")),
            parseDateTime(text(kqlcntMain, "publicDate"))
        );

        BidSheetRow row = BidSheetRowBuilder.create()
            .tmbtNumber(tmbtNumber)
            .packageName(packageName)
            .procuringEntity(procuringEntity)
            .contractPerformanceFolder(contractFolderName)
            .parentFolderName(parentFolderName)
            .estimatedBudget(estimatedBudget)
            .postedDate(postedDate)
            .packageExecutionTime(packageExecutionTime)
            .winningBidPrice(winningBidPrice)
            .status(resolveStatus(
                resolved.contractInfo(),
                searchResult,
                tbmt,
                kqlcntMain,
                bidClosingTime,
                bidOpenTime,
                existingStatuses.get(rowKey(tmbtNumber, parentFolderName, contractFolderName))
            ))
            .bidClosingTime(bidClosingTime)
            .remainingTimeToClosing(remainingToClosing(bidClosingTime))
            .folderLink(folderPath)
            .tenderLink(record.detailUrl())
            .build();
        String sheetHash = sheetRowHash(row);
        ArtifactFingerprintStore.updateHashes(record.key(), sheetHash, null, null, null);
        return row;
    }

    private static String sheetRowHash(BidSheetRow row) {
        if (row == null) {
            return null;
        }
        return ArtifactFingerprintService.hashObject(List.of(
            safe(row.tmbtNumber()),
            safe(row.packageName()),
            safe(row.procuringEntity()),
            safe(String.valueOf(row.estimatedBudget())),
            safe(String.valueOf(row.postedDate())),
            safe(String.valueOf(row.winningBidPrice())),
            safe(row.packageExecutionTime()),
            row.status() == null ? "" : row.status().name(),
            safe(String.valueOf(row.bidClosingTime())),
            safe(row.folderLink()),
            safe(row.tenderLink())
        ));
    }

    private static void exportAgentFiles(BidTrackingRecord record, BidApiParams params, ApiBundle bundle) {
        if (record == null || bundle == null
            || !agentExportEnabled
            || !AppFeatures.isMonitorAgentFileDownloadEnabled()) {
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        Path outputDir = autoDownloadFolder(record.folderPath());
        if (outputDir == null) {
            return;
        }
        Utils.ensureDirectory(outputDir);

        String notifyNo = firstNonBlank(
            text(bundle.searchResult(), "notifyNo"),
            params == null ? null : params.notifyNo(),
            record.keyword()
        );
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AgentFileRef> refs = new ArrayList<>();
        List<AgentFileRef> downloadRefs = new ArrayList<>();

        JsonNode kqlcntMain = bundle.kqlcnt() == null
            ? null
            : bundle.kqlcnt().path("bideContractorInputResultDTO");
        addUniqueRefs(refs, seen, KqlcntAgentFileExtractor.from(kqlcntMain));
        addUniqueRefs(refs, seen, TbmtAgentFileExtractor.clarificationRefs(bundle.clarification()));
        addUniqueRefs(refs, seen, TbmtAgentFileExtractor.petitionRefs(bundle.petition()));
        downloadRefs.addAll(refs);
        if (shouldFetchBbmtOpenApis()) {
            List<AgentFileRef> bbmtRefs = BbmtAgentFileExtractor.extract(bundle.lotOpen(), bundle.bidOpen(), notifyNo);
            addUniqueRefs(refs, seen, bbmtRefs);
            if (!bbmtSeleniumPreferred) {
                addUniqueRefs(downloadRefs, new LinkedHashSet<>(downloadRefIds(downloadRefs)), bbmtRefs);
            }
        }

        if (refs.isEmpty()) {
            return;
        }
        for (AgentFileRef ref : refs) {
            logAgentFound(record, ref, outputDir);
        }
        if (downloadRefs.isEmpty()) {
            Utils.logStatus(
                record.keyword(),
                "INFO",
                0,
                "BBMT API ref found but Selenium pending | folder=" + outputDir.toAbsolutePath().normalize()
            );
            return;
        }
        Utils.logStatus(
            record.keyword(),
            "INFO",
            0,
            "Agent download queued: files=" + downloadRefs.size() + " folder=" + outputDir.toAbsolutePath().normalize()
        );
        if (downloadRefs.size() == 1 || EgpAgentFileDownloadService.getMaxConcurrency() <= 1) {
            AtomicInteger completed = new AtomicInteger();
            AtomicInteger downloaded = new AtomicInteger();
            for (AgentFileRef ref : downloadRefs) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                logAgentStart(record, ref, outputDir);
                logAgentResult(
                    record,
                    notifyNo,
                    ref,
                    EgpAgentFileDownloadService.downloadIfMissing(outputDir, ref),
                    completed.incrementAndGet(),
                    downloaded,
                    downloadRefs.size(),
                    outputDir
                );
            }
            return;
        }
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger downloaded = new AtomicInteger();
        downloadRefs.parallelStream().forEach(ref -> {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            logAgentStart(record, ref, outputDir);
            logAgentResult(
                record,
                notifyNo,
                ref,
                EgpAgentFileDownloadService.downloadIfMissing(outputDir, ref),
                completed.incrementAndGet(),
                downloaded,
                downloadRefs.size(),
                outputDir
            );
        });
    }

    private static Set<String> downloadRefIds(List<AgentFileRef> refs) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (refs == null) {
            return ids;
        }
        for (AgentFileRef ref : refs) {
            if (ref != null && !isBlankOrUndefined(ref.fileId())) {
                ids.add(ref.fileId());
            }
        }
        return ids;
    }

    private static void addUniqueRefs(List<AgentFileRef> target, Set<String> seen, List<AgentFileRef> refs) {
        if (refs == null) {
            return;
        }
        for (AgentFileRef ref : refs) {
            if (ref == null || isBlankOrUndefined(ref.fileId()) || seen.contains(ref.fileId())) {
                continue;
            }
            seen.add(ref.fileId());
            target.add(ref);
        }
    }

    private static void logAgentFound(BidTrackingRecord record, AgentFileRef ref, Path outputDir) {
        Utils.logStatus(
            record == null ? null : record.keyword(),
            "INFO",
            0,
            "Agent file found: " + agentFileDescriptor(ref)
                + " | folder=" + (outputDir == null ? "-" : outputDir.toAbsolutePath().normalize())
        );
    }

    private static void logAgentStart(BidTrackingRecord record, AgentFileRef ref, Path outputDir) {
        Utils.logStatus(
            record == null ? null : record.keyword(),
            "INFO",
            0,
            "Agent downloading: " + agentFileDescriptor(ref)
                + " | folder=" + (outputDir == null ? "-" : outputDir.toAbsolutePath().normalize())
        );
    }

    private static void logAgentResult(
        BidTrackingRecord record,
        String notifyNo,
        AgentFileRef ref,
        EgpAgentFileDownloadService.Result result,
        int completed,
        AtomicInteger downloaded,
        int total,
        Path outputDir
    ) {
        if (result == null) {
            return;
        }
        String label = notifyNo == null || notifyNo.isBlank() ? "package" : notifyNo;
        String keyword = record == null ? label : record.keyword();
        String folder = outputDir == null ? "-" : outputDir.toAbsolutePath().normalize().toString();
        switch (result.outcome()) {
            case SKIPPED_EXISTS -> {
                int ok = downloaded.incrementAndGet();
                Utils.logStatus(keyword, "INFO", 0,
                    "Agent skipped existing: " + agentFileDescriptor(ref)
                        + " | Downloaded files: " + ok + "/" + total
                        + " | completed=" + completed + "/" + total
                        + " | folder=" + folder);
            }
            case SKIPPED_INVALID_REF -> Utils.logStatus(keyword, "WARN", 0,
                "Agent skipped invalid ref: " + agentFileDescriptor(ref) + " | "
                    + agentProgress(downloaded.get(), completed, total, folder));
            case DOWNLOADED -> {
                int ok = downloaded.incrementAndGet();
                Utils.logStatus(keyword, "INFO", 0,
                    "Agent downloaded: " + agentFileDescriptor(ref)
                        + " | Downloaded files: " + ok + "/" + total
                        + " | completed=" + completed + "/" + total
                        + " | folder=" + folder);
            }
            case AGENT_UNAVAILABLE -> Utils.logStatus(keyword, "WARN", 0,
                "Agent unavailable: " + agentFileDescriptor(ref) + " | " + result.message()
                    + " | " + agentProgress(downloaded.get(), completed, total, folder));
            case FORBIDDEN -> Utils.logStatus(keyword, "WARN", 0,
                "Agent failed 403: " + agentFileDescriptor(ref)
                    + " | Open muasamcong portal once or check VNeGP agent. | "
                    + agentProgress(downloaded.get(), completed, total, folder));
            case INVALID_BODY, FAILED -> Utils.logStatus(keyword, "WARN", 0,
                "Agent failed: " + agentFileDescriptor(ref) + " | " + result.message()
                    + " | " + agentProgress(downloaded.get(), completed, total, folder));
            default -> Utils.logStatus(keyword, "INFO", 0,
                "Agent " + result.outcome() + ": " + agentFileDescriptor(ref) + " | " + result.message()
                    + " | " + agentProgress(downloaded.get(), completed, total, folder));
        }
    }

    private static String agentProgress(int downloaded, int completed, int total, String folder) {
        return "Downloaded files: " + downloaded + "/" + total
            + " | completed=" + completed + "/" + total
            + " | folder=" + folder;
    }

    private static String agentFileDescriptor(AgentFileRef ref) {
        return "file=" + agentFileLabel(ref) + " | fileId=" + agentFileId(ref);
    }

    private static String agentFileLabel(AgentFileRef ref) {
        if (ref == null) {
            return "file";
        }
        if (ref.fileName() != null && !ref.fileName().isBlank()) {
            return ref.fileName();
        }
        return ref.fileId() == null || ref.fileId().isBlank() ? "file" : ref.fileId();
    }

    private static String agentFileId(AgentFileRef ref) {
        return ref == null || ref.fileId() == null || ref.fileId().isBlank() ? "-" : ref.fileId();
    }

    private static void logAgentResult(
        String notifyNo,
        AgentFileRef ref,
        EgpAgentFileDownloadService.Result result
    ) {
        if (result == null) {
            return;
        }
        String label = notifyNo == null || notifyNo.isBlank() ? "package" : notifyNo;
        String fileLabel = ref == null || ref.fileName() == null ? ref == null ? "file" : ref.fileId() : ref.fileName();
        switch (result.outcome()) {
            case SKIPPED_EXISTS, SKIPPED_INVALID_REF -> {
            }
            case DOWNLOADED -> Utils.logPlain("Agent download OK [" + label + "]: " + fileLabel);
            case AGENT_UNAVAILABLE -> Utils.logPlain(
                "Agent download skipped [" + label + "]: VNeGP agent unavailable — " + result.message());
            case FORBIDDEN -> Utils.logPlain(
                "Agent download failed [" + label + "]: 403 Forbidden for " + fileLabel
                    + ". Open muasamcong portal once or check VNeGP agent.");
            case INVALID_BODY, FAILED -> Utils.logPlain(
                "Agent download failed [" + label + "]: " + fileLabel + " — " + result.message());
            default -> Utils.logPlain("Agent download [" + label + "]: " + result.outcome() + " — " + result.message());
        }
    }

    private static boolean agentRefMissing(Path autoDownloadDir, AgentFileRef ref) {
        if (ref == null || isBlankOrUndefined(ref.fileId())) {
            return false;
        }
        if (autoDownloadDir == null) {
            return true;
        }
        return EgpAgentFileDownloadService.anyRefMissingOnDisk(autoDownloadDir, List.of(ref));
    }

    private static void exportContractorReport(BidTrackingRecord record, JsonNode kqlcntMain) {
        if (record == null || kqlcntMain == null || kqlcntMain.isMissingNode() || kqlcntMain.isNull()) {
            return;
        }
        String notifyNo = text(kqlcntMain, "notifyNo");
        if (notifyNo == null || notifyNo.isBlank()) {
            return;
        }

        Path outputDir = autoDownloadFolder(record.folderPath());
        if (outputDir == null) {
            return;
        }
        Utils.ensureDirectory(outputDir);
        Path reportFile = outputDir.resolve(safeFileName("Nhà thầu trúng thầu - " + notifyNo + ".csv"));
        if (artifactFileExists(reportFile)) {
            return;
        }

        List<ContractorReportRow> rows = contractorReportRows(kqlcntMain);
        if (rows.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        appendCsvRow(sb, List.of(
            "STT",
            "Mã định danh",
            "Mã số thuế",
            "Tên nhà thầu",
            "Giá dự thầu (VND)",
            "Điểm kỹ thuật (nếu có)",
            "Giá đánh giá (nếu có)",
            "Giá dự thầu sau hiệu chỉnh sai lệch thừa (nếu có), giảm giá (nếu có)",
            "Giá trúng thầu (VND)",
            "Thời gian thực hiện gói thầu",
            "Thời gian thực hiện gói thầu chi tiết",
            "Thời gian thực hiện hợp đồng",
            "Các nội dung khác (nếu có)"
        ));
        for (int i = 0; i < rows.size(); i++) {
            ContractorReportRow row = rows.get(i);
            appendCsvRow(sb, List.of(
                Integer.toString(i + 1),
                safe(row.orgCode()),
                safe(row.taxCode()),
                safe(row.orgFullname()),
                safe(row.bidPrice()),
                safe(row.techScore()),
                safe(row.evalBidPrice()),
                safe(row.finalBidPrice()),
                safe(row.winningBidPrice()),
                safe(row.packageExecutionTime()),
                safe(row.packageExecutionTimeDetail()),
                safe(row.contractExecutionTime()),
                safe(row.otherContent())
            ));
        }
        String csvHash = ArtifactFingerprintService.hashObject(rows);
        try {
            Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8);
            ArtifactFingerprintStore.updateHashes(record.key(), null, csvHash, null, null);
            Utils.logPlain("Contractor report CSV exported: " + reportFile.toAbsolutePath());
        } catch (Exception ex) {
            Utils.logPlain("Unable to write contractor report CSV for " + notifyNo + ": " + ex.getMessage());
        }
    }

    private static List<ContractorReportRow> contractorReportRows(JsonNode kqlcntMain) {
        List<ContractorReportRow> rows = new ArrayList<>();
        JsonNode lots = kqlcntMain == null ? null : kqlcntMain.path("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return rows;
        }
        for (JsonNode lot : lots) {
            JsonNode contractors = lot.path("contractorList");
            if (contractors == null || !contractors.isArray()) {
                continue;
            }
            for (JsonNode contractor : contractors) {
                rows.add(new ContractorReportRow(
                    text(contractor, "orgCode"),
                    text(contractor, "taxCode"),
                    text(contractor, "orgFullname"),
                    money(contractor, "lotPrice"),
                    text(contractor, "techScore"),
                    money(contractor, "evalBidPrice"),
                    money(contractor, "lotFinalPrice"),
                    money(contractor, "bidWiningPrice"),
                    contractPeriod(contractor),
                    text(contractor, "bidExecutionTime"),
                    text(contractor, "cperiodText"),
                    text(contractor, "otherContent")
                ));
            }
        }
        return rows;
    }

    private static void appendCsvRow(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csv(values.get(i)));
        }
        sb.append(System.lineSeparator());
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static void exportGoodsBidExcel(BidTrackingRecord record, JsonNode kqlcntMain) {
        if (record == null || kqlcntMain == null || kqlcntMain.isMissingNode() || kqlcntMain.isNull()) {
            return;
        }
        String notifyNo = text(kqlcntMain, "notifyNo");
        if (notifyNo == null || notifyNo.isBlank()) {
            return;
        }

        Path outputDir = autoDownloadFolder(record.folderPath());
        if (outputDir == null) {
            return;
        }
        Utils.ensureDirectory(outputDir);
        Path outputFile = outputDir.resolve(safeFileName("Bảng dự thầu hàng hoá - " + notifyNo + ".xlsx"));
        if (artifactFileExists(outputFile)) {
            return;
        }

        List<GoodsBidRow> goodsRows = goodsBidRows(kqlcntMain);
        if (goodsRows.isEmpty()) {
            return;
        }

        String goodsHash = ArtifactFingerprintService.hashObject(List.of(
            safe(notifyNo),
            safe(text(kqlcntMain, "bidName")),
            goodsRows
        ));
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Bang du thau hang hoa");
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle labelStyle = labelStyle(workbook);
            CellStyle valueStyle = valueStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle bodyStyle = bodyStyle(workbook);
            CellStyle numberStyle = numberStyle(workbook);

            Row titleRow = sheet.createRow(0);
            createCell(titleRow, 0, "THÔNG TIN CHUNG", titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 12));

            Row notifyRow = sheet.createRow(1);
            createCell(notifyRow, 0, "Mã TBMT", labelStyle);
            createCell(notifyRow, 1, notifyNo, valueStyle);

            Row nameRow = sheet.createRow(2);
            createCell(nameRow, 0, "Tên gói thầu", labelStyle);
            createCell(nameRow, 1, text(kqlcntMain, "bidName"), valueStyle);
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 12));

            List<String> headers = List.of(
                "STT",
                "Danh mục hàng hóa",
                "Ký mã hiệu",
                "Nhãn hiệu",
                "Xuất xứ (quốc gia, vùng lãnh thổ sản xuất)",
                "Hãng sản xuất",
                "Cấu hình, tính năng kỹ thuật cơ bản",
                "Đơn vị tính",
                "Khối lượng",
                "Mã HS",
                "Đơn giá trúng thầu",
                "Thành tiền đã bao gồm thuế, phí, lệ phí (nếu có)",
                "Thời gian giao hàng (ngày)/ Tiến độ cung cấp"
            );
            Row headerRow = sheet.createRow(4);
            headerRow.setHeightInPoints(34);
            for (int i = 0; i < headers.size(); i++) {
                createCell(headerRow, i, headers.get(i), headerStyle);
            }

            BigInteger total = BigInteger.ZERO;
            int rowIndex = 5;
            for (int i = 0; i < goodsRows.size(); i++) {
                GoodsBidRow goods = goodsRows.get(i);
                Row row = sheet.createRow(rowIndex++);
                createCell(row, 0, Integer.toString(i + 1), bodyStyle);
                createCell(row, 1, goods.name(), bodyStyle);
                createCell(row, 2, goods.codeGood(), bodyStyle);
                createCell(row, 3, goods.labelGood(), bodyStyle);
                createCell(row, 4, goods.origin(), bodyStyle);
                createCell(row, 5, goods.manufacturer(), bodyStyle);
                createCell(row, 6, goods.feature(), bodyStyle);
                createCell(row, 7, goods.uom(), bodyStyle);
                createCell(row, 8, goods.qty(), bodyStyle);
                createCell(row, 9, goods.hsCode(), bodyStyle);
                createNumberCell(row, 10, goods.unitPrice(), numberStyle);
                createNumberCell(row, 11, goods.amount(), numberStyle);
                createCell(row, 12, goods.deliveryTime(), bodyStyle);
                if (goods.amount() != null) {
                    total = total.add(goods.amount());
                }
            }

            Row totalRow = sheet.createRow(rowIndex);
            createCell(totalRow, 0, "", bodyStyle);
            createCell(totalRow, 1, "Tổng cộng giá dự thầu của hàng hóa đã bao gồm thuế, phí, lệ phí (nếu có)", labelStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 1, 10));
            createNumberCell(totalRow, 11, total, numberStyle);
            createCell(totalRow, 12, "", bodyStyle);

            int[] widths = {8, 34, 24, 24, 28, 28, 42, 14, 14, 14, 18, 28, 34};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            Files.write(outputFile, workbookToBytes(workbook));
            ArtifactFingerprintStore.updateHashes(record.key(), null, null, goodsHash, null);
            Utils.logPlain("Goods bid Excel exported: " + outputFile.toAbsolutePath());
        } catch (Exception ex) {
            Utils.logPlain("Unable to export goods bid Excel for " + notifyNo + ": " + ex.getMessage());
        }
    }

    private static byte[] workbookToBytes(Workbook workbook) throws Exception {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static List<GoodsBidRow> goodsBidRows(JsonNode kqlcntMain) {
        List<GoodsBidRow> rows = new ArrayList<>();
        JsonNode lots = kqlcntMain == null ? null : kqlcntMain.path("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return rows;
        }
        for (JsonNode lot : lots) {
            String winningCode = text(lot, "winningCode");
            String goodsListRaw = text(lot, "goodsList");
            if (winningCode == null || goodsListRaw == null) {
                continue;
            }
            try {
                JsonNode goodsList = MAPPER.readTree(goodsListRaw);
                if (goodsList == null || !goodsList.isArray()) {
                    continue;
                }
                for (JsonNode goodsEntry : goodsList) {
                    if (!winningCode.equals(text(goodsEntry, "contractorCode"))) {
                        continue;
                    }
                    JsonNode table = goodsEntry.path("formValue").path("lotContent").path("Table");
                    if (table == null || !table.isArray()) {
                        continue;
                    }
                    for (JsonNode item : table) {
                        rows.add(new GoodsBidRow(
                            text(item, "name"),
                            text(item, "codeGood"),
                            firstText(item, "labelGood", "lableGood"),
                            text(item, "origin"),
                            text(item, "manufacturer"),
                            firstText(item, "feature", "technique"),
                            text(item, "uom"),
                            text(item, "qty"),
                            firstText(item, "hsCode", "maHs", "maHS"),
                            bigInteger(item, "bidPrice"),
                            bigInteger(item, "amount"),
                            firstText(item, "cPeriod", "cperiod", "deliveryTime")
                        ));
                    }
                }
            } catch (Exception ex) {
                Utils.logPlain("Unable to parse goodsList: " + ex.getMessage());
            }
        }
        return rows;
    }

    private static CellStyle titleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private static CellStyle labelStyle(Workbook workbook) {
        CellStyle style = borderedStyle(workbook);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static CellStyle valueStyle(Workbook workbook) {
        return borderedStyle(workbook);
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = borderedStyle(workbook);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        return style;
    }

    private static CellStyle bodyStyle(Workbook workbook) {
        CellStyle style = borderedStyle(workbook);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }

    private static CellStyle numberStyle(Workbook workbook) {
        CellStyle style = bodyStyle(workbook);
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
        return style;
    }

    private static CellStyle borderedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        return style;
    }

    private static void createCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void createNumberCell(Row row, int column, BigInteger value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    public static Map<String, BidStatus> loadExistingStatuses() {
        LinkedHashMap<String, BidStatus> statuses = new LinkedHashMap<>();
        Path jsonFile = Utils.dataFile("bid_sheet_rows.json");
        if (!Files.exists(jsonFile)) {
            return statuses;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(jsonFile));
            JsonNode rows = root == null ? null : (root.isArray() ? root : root.path("rows"));
            if (rows == null || !rows.isArray()) {
                return statuses;
            }
            for (JsonNode row : rows) {
                BidStatus status = status(text(row, "status"));
                if (status != null) {
                    statuses.put(rowKey(
                        text(row, "tmbtNumber"),
                        text(row, "parentFolderName"),
                        text(row, "contractPerformanceFolder")
                    ), status);
                }
            }
        } catch (Exception ex) {
            Utils.logPlain("Unable to preserve existing bid sheet statuses: " + ex.getMessage());
        }
        return statuses;
    }

    private static BidStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BidStatus.valueOf(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static BidStatus resolveStatus(
        JsonNode contractInfo,
        JsonNode searchResult,
        JsonNode tbmt,
        JsonNode kqlcntMain,
        LocalDateTime bidClosingTime,
        LocalDateTime bidOpenTime,
        BidStatus existingStatus
    ) {
        if (hasContractInformation(contractInfo)) {
            return BidStatus.CONTRACT_INFORMATION_AVAILABLE;
        }
        if (isTbmtCancelled(searchResult, tbmt)) {
            return BidStatus.TBMT_CANCELLED;
        }
        if (hasSelectionResult(kqlcntMain, contractInfo)) {
            return BidStatus.CONTRACTOR_SELECTION_RESULT_AVAILABLE;
        }
        LocalDateTime now = LocalDateTime.now();
        if (bidOpenTime != null && !bidOpenTime.isAfter(now)) {
            return BidStatus.BID_OPENED;
        }
        if (bidClosingTime != null) {
            return bidClosingTime.isAfter(now)
                ? BidStatus.INVITATION_OPEN
                : BidStatus.BIDDING_CLOSED;
        }
        return existingStatus;
    }

    private static boolean hasSelectionResult(JsonNode kqlcntMain, JsonNode contractInfo) {
        if (hasContractInformation(contractInfo)) {
            return false;
        }
        return kqlcntMain != null && !kqlcntMain.isMissingNode() && !kqlcntMain.isNull() && kqlcntMain.size() > 0;
    }

    private static boolean hasContractInformation(JsonNode contractInfo) {
        if (contractInfo == null || contractInfo.isMissingNode() || contractInfo.isNull()) {
            return false;
        }
        JsonNode count = firstExisting(contractInfo, "Count", "count", "total", "totalElements");
        if (count != null && count.canConvertToInt()) {
            return count.asInt() > 0;
        }
        return hasApiContent(contractInfo);
    }

    private static boolean isTbmtCancelled(JsonNode searchResult, JsonNode tbmt) {
        if (isCancelledNotifyStatus(text(searchResult, "status"))) {
            return true;
        }
        JsonNode tbmtMain = tbmt == null ? null : tbmt.path("bidoNotifyContractorM");
        if (isCancelledNotifyStatus(text(tbmtMain, "status"))) {
            return true;
        }
        JsonNode cancel = tbmt == null ? null : tbmt.path("bidCancelingResponse");
        if (cancel == null || cancel.isMissingNode() || cancel.isNull() || cancel.size() == 0) {
            return false;
        }
        if (isCancelledNotifyStatus(text(cancel, "status"))) {
            return true;
        }
        return text(cancel, "cancelDate") != null || text(cancel, "cancelReason") != null;
    }

    private static boolean isCancelledNotifyStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toUpperCase();
        return "CANCELED".equals(normalized)
            || "CANCELLED".equals(normalized)
            || "03".equals(normalized);
    }

    private static String rowKey(String tmbtNumber, String parentFolderName, String contractFolderName) {
        return safe(tmbtNumber) + "|" + safe(parentFolderName) + "|" + safe(contractFolderName);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static JsonNode postJson(URI uri, String body) {
        return postJson(uri, body, true);
    }

    private static int resolveBbmtPackType(JsonNode tbmt, JsonNode searchResult) {
        JsonNode tbmtMain = tbmt == null ? null : tbmt.path("bidoNotifyContractorM");
        if (tbmtMain != null && tbmtMain.has("packType") && tbmtMain.get("packType").canConvertToInt()) {
            return tbmtMain.get("packType").asInt();
        }
        if (searchResult != null && searchResult.has("packType") && searchResult.get("packType").canConvertToInt()) {
            return searchResult.get("packType").asInt();
        }
        return 1;
    }

    private static int resolveBbmtViewType(JsonNode tbmt, JsonNode searchResult) {
        JsonNode tbmtMain = tbmt == null ? null : tbmt.path("bidoNotifyContractorM");
        if (tbmtMain != null && tbmtMain.has("viewType") && tbmtMain.get("viewType").canConvertToInt()) {
            return tbmtMain.get("viewType").asInt();
        }
        if (searchResult != null && searchResult.has("viewType") && searchResult.get("viewType").canConvertToInt()) {
            return searchResult.get("viewType").asInt();
        }
        return 0;
    }

    private static JsonNode postJsonOptional(URI uri, String body) {
        return postJson(uri, body, false);
    }

    private static JsonNode postJson(URI uri, String body, boolean logFailures) {
        if (uri == null || body == null || body.contains(":null")) {
            return null;
        }
        return ApiJsonHttpClient.postJson(
            HTTP_CLIENT,
            MAPPER,
            uri,
            body,
            Duration.ofSeconds(30),
            "Bid sheet API request " + uri,
            logFailures
        );
    }

    private static String winningContractPeriodText(JsonNode kqlcntMain) {
        JsonNode lots = kqlcntMain == null ? null : kqlcntMain.path("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return null;
        }
        for (JsonNode lot : lots) {
            JsonNode contractors = lot.path("contractorList");
            if (contractors == null || !contractors.isArray()) {
                continue;
            }
            for (JsonNode contractor : contractors) {
                if (contractor.path("bidResult").asInt(-1) == 1) {
                    return normalizeContractPeriodText(text(contractor, "cperiodText"));
                }
            }
        }
        return null;
    }

    private static BigInteger winningBidPrice(JsonNode kqlcntMain) {
        JsonNode lots = kqlcntMain == null ? null : kqlcntMain.path("lotResultDTO");
        if (lots == null || !lots.isArray()) {
            return null;
        }
        for (JsonNode lot : lots) {
            JsonNode contractors = lot.path("contractorList");
            if (contractors == null || !contractors.isArray()) {
                continue;
            }
            for (JsonNode contractor : contractors) {
                if (contractor.path("bidResult").asInt(-1) == 1) {
                    return bigInteger(contractor, "bidWiningPrice");
                }
            }
        }
        return null;
    }

    private static String normalizeContractPeriodText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        if (lower.contains("ngày") || lower.contains("ngay")) {
            return trimmed;
        }
        return trimmed + " ngày";
    }

    private static String contractPeriod(JsonNode contractor) {
        JsonNode value = contractor == null ? null : contractor.get("cperiod");
        if (value == null || value.isNull()) {
            return null;
        }
        String period = value.asText();
        if (period == null || period.isBlank()) {
            return null;
        }
        String unit = text(contractor, "cperiodUnit");
        if (unit == null || unit.isBlank() || "D".equalsIgnoreCase(unit)) {
            return period.trim() + " ngày";
        }
        return period.trim() + " " + unit.trim();
    }

    private static String money(JsonNode node, String field) {
        BigInteger value = bigInteger(node, field);
        if (value == null) {
            return null;
        }
        String digits = value.toString();
        StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3);
        int first = digits.length() % 3;
        if (first == 0) {
            first = 3;
        }
        sb.append(digits, 0, first);
        for (int i = first; i < digits.length(); i += 3) {
            sb.append('.').append(digits, i, i + 3);
        }
        return sb.toString();
    }

    private record ContractorReportRow(
        String orgCode,
        String taxCode,
        String orgFullname,
        String bidPrice,
        String techScore,
        String evalBidPrice,
        String finalBidPrice,
        String winningBidPrice,
        String packageExecutionTime,
        String packageExecutionTimeDetail,
        String contractExecutionTime,
        String otherContent
    ) {
    }

    private static Duration remainingToClosing(LocalDateTime bidClosingTime) {
        if (bidClosingTime == null) {
            return null;
        }
        long seconds = Duration.between(LocalDateTime.now(), bidClosingTime).getSeconds();
        return Duration.ofSeconds(Math.max(0, seconds));
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static BigInteger bigInteger(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value != null && value.isArray()) {
            value = value.size() == 0 ? null : value.get(0);
        }
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText()).toBigInteger();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value != null && value.isArray()) {
            value = value.size() == 0 ? null : value.get(0);
        }
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String firstText(JsonNode node, String... fields) {
        if (fields == null) {
            return null;
        }
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String quote(String value) {
        if (isBlankOrUndefined(value)) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "null";
        }
    }

    private static boolean isBlankOrUndefined(String value) {
        return value == null || value.isBlank() || "undefined".equalsIgnoreCase(value.trim());
    }

    private static String folderName(String folderPath) {
        Path path = path(folderPath);
        return path == null || path.getFileName() == null ? null : path.getFileName().toString();
    }

    private static Path autoDownloadFolder(String folderPath) {
        Path path = path(folderPath);
        return path == null ? null : path.resolve("auto-download");
    }

    private static boolean artifactFileExists(Path file) {
        return file != null && Files.isRegularFile(file);
    }

    private static String safeFileName(String value) {
        String name = value == null || value.isBlank() ? "Bang du thau hang hoa.xlsx" : value.trim();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String parentFolderName(String folderPath) {
        Path path = path(folderPath);
        return path == null || path.getParent() == null || path.getParent().getFileName() == null
            ? null
            : path.getParent().getFileName().toString();
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private record GoodsBidRow(
        String name,
        String codeGood,
        String labelGood,
        String origin,
        String manufacturer,
        String feature,
        String uom,
        String qty,
        String hsCode,
        BigInteger unitPrice,
        BigInteger amount,
        String deliveryTime
    ) {
    }
}
