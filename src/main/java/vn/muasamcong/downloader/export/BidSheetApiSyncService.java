package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import vn.muasamcong.downloader.core.DownloadWorker;
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
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();
    private static final String BASE_API =
        "https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services";
    private static final URI TBMT_URI = URI.create(BASE_API + "/lcnt_tbmt_ttc_ldt?token=fake");
    private static final URI KQLCNT_URI = URI.create(BASE_API + "/expose/contractor-input-result/get?token=fake");
    private static final URI HSMT_CLARIFICATION_URI = URI.create(BASE_API + "/lcnt_tbmt_yclr?token=fake");
    private static final URI PETITION_URI = URI.create(BASE_API + "/lcnt_tbmt_kn?token=fake");

    private BidSheetApiSyncService() {
    }

    public static int refreshBidSheetRowsFromTracking() {
        List<BidTrackingRecord> records = BidTrackingRecordStore.loadRecords();
        Map<String, BidStatus> existingStatuses = loadExistingStatuses();
        List<BidSheetRow> rows = new ArrayList<>();
        for (BidTrackingRecord record : records) {
            BidSheetRow row = buildRow(record, existingStatuses);
            if (row != null) {
                rows.add(row);
            }
        }
        DownloadWorker.replaceBidInfoRows(rows);
        return rows.size();
    }

    public static DownloadHints resolveDownloadHints(BidApiParams params) {
        if (params == null) {
            return DownloadHints.unknown();
        }

        JsonNode kqlcnt = postJson(KQLCNT_URI, "{\"id\":" + quote(params.inputResultId()) + "}");
        JsonNode kqlcntMain = kqlcnt == null ? null : kqlcnt.path("bideContractorInputResultDTO");
        String decisionFileId = text(kqlcntMain, "decisionFileId");
        String decisionFileName = text(kqlcntMain, "decisionFileName");
        String reportFileId = text(kqlcntMain, "reportFileId");
        String reportFileName = text(kqlcntMain, "reportFileName");
        String goodFileId = text(kqlcntMain, "goodFileId");
        String goodFileName = text(kqlcntMain, "goodFileName");

        boolean hasGoodsData = hasGoodsListData(kqlcntMain);
        boolean hasWinningContractor = hasWinningContractor(kqlcntMain);
        boolean hasClarification = hasClarificationContent(postJson(
            HSMT_CLARIFICATION_URI,
            "{\"notifyNo\":" + quote(params.notifyNo()) + ",\"processApply\":" + quote(params.processApply()) + "}"
        ));
        boolean hasPetition = hasPetitionContent(postJson(
            PETITION_URI,
            "{\"notifyNo\":" + quote(params.notifyNo()) + ",\"processApply\":" + quote(params.processApply()) + "}"
        ));
        return new DownloadHints(
            true,
            hasClarification || hasPetition,
            hasClarification,
            hasPetition,
            !isBlankOrUndefined(params.bidOpenId()),
            !isBlankOrUndefined(decisionFileId),
            !isBlankOrUndefined(reportFileId),
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

        BidApiParams params = record.apiParams();
        JsonNode tbmt = postJson(TBMT_URI, "{\"id\":" + quote(params.notifyId()) + "}");
        JsonNode kqlcnt = postJson(KQLCNT_URI, "{\"id\":" + quote(params.inputResultId()) + "}");

        JsonNode tbmtMain = tbmt == null ? null : tbmt.path("bidoNotifyContractorM");
        JsonNode kqlcntMain = kqlcnt == null ? null : kqlcnt.path("bideContractorInputResultDTO");
        LocalDateTime bidClosingTime = parseDateTime(text(tbmtMain, "bidCloseDate"));
        exportContractorReport(record, kqlcntMain);
        exportGoodsBidExcel(record, kqlcntMain);

        String folderPath = record.folderPath();
        String contractFolderName = folderName(folderPath);
        String parentFolderName = parentFolderName(folderPath);
        String packageExecutionTime = winningContractPeriodText(kqlcntMain);
        BigInteger winningBidPrice = winningBidPrice(kqlcntMain);
        String tmbtNumber = text(kqlcntMain, "notifyNo");

        BidSheetRow row = BidSheetRowBuilder.create()
            .tmbtNumber(tmbtNumber)
            .packageName(text(kqlcntMain, "bidName"))
            .procuringEntity(text(kqlcntMain, "investorName"))
            .contractPerformanceFolder(contractFolderName)
            .parentFolderName(parentFolderName)
            .estimatedBudget(bigInteger(kqlcntMain, "bidEstimatePrice"))
            .postedDate(parseDateTime(text(kqlcntMain, "publicDate")))
            .packageExecutionTime(packageExecutionTime)
            .winningBidPrice(winningBidPrice)
            .status(existingStatuses.get(rowKey(tmbtNumber, parentFolderName, contractFolderName)))
            .bidClosingTime(bidClosingTime)
            .remainingTimeToClosing(remainingToClosing(bidClosingTime))
            .folderLink(folderPath)
            .tenderLink(record.detailUrl())
            .build();
        String sheetHash = ArtifactFingerprintService.hashObject(List.of(
            safe(row.tmbtNumber()),
            safe(row.packageName()),
            safe(row.procuringEntity()),
            safe(String.valueOf(row.estimatedBudget())),
            safe(String.valueOf(row.postedDate())),
            safe(String.valueOf(row.winningBidPrice())),
            safe(row.packageExecutionTime()),
            safe(String.valueOf(row.bidClosingTime())),
            safe(row.folderLink()),
            safe(row.tenderLink())
        ));
        ArtifactFingerprintStore.updateHashes(record.key(), sheetHash, null, null, null);
        return row;
    }

    private static void exportContractorReport(BidTrackingRecord record, JsonNode kqlcntMain) {
        if (record == null || kqlcntMain == null || kqlcntMain.isMissingNode() || kqlcntMain.isNull()) {
            return;
        }
        String notifyNo = text(kqlcntMain, "notifyNo");
        if (notifyNo == null || notifyNo.isBlank()) {
            return;
        }
        List<ContractorReportRow> rows = contractorReportRows(kqlcntMain);
        if (rows.isEmpty()) {
            return;
        }

        Path outputDir = autoDownloadFolder(record.folderPath());
        if (outputDir == null) {
            return;
        }
        Utils.ensureDirectory(outputDir);
        Path reportFile = outputDir.resolve(safeFileName("Nhà thầu trúng thầu - " + notifyNo + ".csv"));

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
        if (ArtifactFingerprintStore.isHashUnchanged(
            record.key(), ArtifactFingerprintStore.HashType.CONTRACTOR_CSV, csvHash)) {
            Utils.logPlain("Contractor report CSV unchanged. Skipping: " + reportFile.toAbsolutePath());
            return;
        }
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
        List<GoodsBidRow> goodsRows = goodsBidRows(kqlcntMain);
        if (goodsRows.isEmpty()) {
            return;
        }

        Path outputDir = autoDownloadFolder(record.folderPath());
        if (outputDir == null) {
            return;
        }
        Utils.ensureDirectory(outputDir);
        Path outputFile = outputDir.resolve(safeFileName("Bảng dự thầu hàng hoá - " + notifyNo + ".xlsx"));
        String goodsHash = ArtifactFingerprintService.hashObject(List.of(
            safe(notifyNo),
            safe(text(kqlcntMain, "bidName")),
            goodsRows
        ));
        if (ArtifactFingerprintStore.isHashUnchanged(
            record.key(), ArtifactFingerprintStore.HashType.GOODS_EXCEL, goodsHash)) {
            Utils.logPlain("Goods bid Excel unchanged. Skipping: " + outputFile.toAbsolutePath());
            return;
        }

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

    private static Map<String, BidStatus> loadExistingStatuses() {
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

    private static String rowKey(String tmbtNumber, String parentFolderName, String contractFolderName) {
        return safe(tmbtNumber) + "|" + safe(parentFolderName) + "|" + safe(contractFolderName);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static JsonNode postJson(URI uri, String body) {
        if (uri == null || body == null || body.contains(":null")) {
            return null;
        }
        return ApiJsonHttpClient.postJson(
            HTTP_CLIENT,
            MAPPER,
            uri,
            body,
            Duration.ofSeconds(30),
            "Bid sheet API request " + uri
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
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigInteger(value.asText());
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
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
