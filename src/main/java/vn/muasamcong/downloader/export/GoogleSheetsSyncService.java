package vn.muasamcong.downloader.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.muasamcong.downloader.model.BidSheetRow;
import vn.muasamcong.downloader.model.BidSheetRowBuilder;
import vn.muasamcong.downloader.model.BidStatus;
import vn.muasamcong.downloader.store.GoogleSheetsConfigStore;
import vn.muasamcong.downloader.util.Utils;

public final class GoogleSheetsSyncService {

    private static final String DEFAULT_SHEET_NAME = "Sheet1";
    private static final String INDEX_SHEET_NAME = "MucLuc";
    private static final String DATA_RANGE_START_A1 = "A1";
    private static final String INDEX_DATA_RANGE_START_A1 = "A1";
    private static final int STATUS_COLUMN_INDEX = 5; // column F, zero-based
    private static final int REMAINING_COLUMN_INDEX = 7; // column H, zero-based
    private static final int TOTAL_COLUMN_COUNT = 9; // A..I
    private static final DateTimeFormatter INDEX_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final List<String> OUTPUT_HEADERS = List.of(
        "Số TBMT",
        "Tên gói thầu",
        "CĐT",
        "Thư mục THHD",
        "Dự toán",
        "Trạng thái",
        "Thời điểm đóng thầu",
        "Còn lại",
        "Link thư mục THHD"
    );
    private static final List<String> INDEX_HEADERS = List.of(
        "STT",
        "Tên Sheet",
        "Tổng số gói thầu",
        "Tóm tắt trạng thái",
        "Cập nhật lúc",
        "Điều hướng"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private GoogleSheetsSyncService() {
    }

    public static void syncFromJsonIfEnabled(Path jsonFile) {
        GoogleSheetsConfigStore.GoogleSheetsConfig config = GoogleSheetsConfigStore.resolve();
        if (!config.autoSync()) {
            return;
        }

        try {
            int rowCount = syncFromJson(jsonFile, config);
            Utils.logPlain("Google Sheets sync completed. Rows: " + rowCount);
        } catch (Exception ex) {
            Utils.logPlain("Google Sheets sync failed: " + ex.getMessage());
        }
    }

    public static void syncFromJsonNow(Path jsonFile) throws Exception {
        GoogleSheetsConfigStore.GoogleSheetsConfig config = GoogleSheetsConfigStore.resolve();
        syncFromJson(jsonFile, config);
    }

    private static int syncFromJson(Path jsonFile, GoogleSheetsConfigStore.GoogleSheetsConfig config) throws Exception {
        List<BidSheetRow> rows = readRowsFromJson(jsonFile);
        syncRows(rows, config);
        return rows.size();
    }

    private static List<BidSheetRow> readRowsFromJson(Path jsonFile) throws IOException {
        if (jsonFile == null || !Files.exists(jsonFile)) {
            return List.of();
        }
        JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(jsonFile));
        JsonNode rowsNode = extractRowsNode(root);
        if (rowsNode == null || !rowsNode.isArray()) {
            return List.of();
        }

        List<BidSheetRow> rows = new ArrayList<>();
        for (JsonNode node : rowsNode) {
            rows.add(toBidSheetRow(node));
        }
        return rows;
    }

    private static JsonNode extractRowsNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        JsonNode rowsNode = root.get("rows");
        return rowsNode != null && rowsNode.isArray() ? rowsNode : null;
    }

    private static BidSheetRow toBidSheetRow(JsonNode node) {
        String statusRaw = textValue(node, "status");
        BidStatus status = null;
        if (statusRaw != null && !statusRaw.isBlank()) {
            try {
                status = BidStatus.valueOf(statusRaw);
            } catch (IllegalArgumentException ignored) {
                status = null;
            }
        }

        LocalDateTime bidClosingTime = null;
        String bidClosingRaw = textValue(node, "bidClosingTime");
        if (bidClosingRaw != null && !bidClosingRaw.isBlank()) {
            try {
                bidClosingTime = LocalDateTime.parse(bidClosingRaw);
            } catch (Exception ignored) {
                bidClosingTime = null;
            }
        }

        Duration remaining = null;
        JsonNode remainingNode = node.get("remainingTimeSeconds");
        if (remainingNode != null && remainingNode.isNumber()) {
            remaining = Duration.ofSeconds(remainingNode.asLong());
        }

        return BidSheetRowBuilder.create()
            .tmbtNumber(textValue(node, "tmbtNumber"))
            .packageName(textValue(node, "packageName"))
            .procuringEntity(textValue(node, "procuringEntity"))
            .contractPerformanceFolder(textValue(node, "contractPerformanceFolder"))
            .parentFolderName(textValue(node, "parentFolderName"))
            .estimatedBudget(parseEstimatedBudget(node.get("estimatedBudget")))
            .status(status)
            .bidClosingTime(bidClosingTime)
            .remainingTimeToClosing(remaining)
            .folderLink(textValue(node, "folderLink"))
            .build();
    }

    private static BigInteger parseEstimatedBudget(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            String raw = node.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return null;
            }
            return new BigInteger(digits);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    private static void syncRows(List<BidSheetRow> rows, GoogleSheetsConfigStore.GoogleSheetsConfig config) throws Exception {
        String spreadsheetId = requireValue(config.spreadsheetId(), GoogleSheetsConfigStore.ENV_SPREADSHEET_ID);
        String fallbackSheetName = defaultIfBlank(config.sheetName(), DEFAULT_SHEET_NAME);
        Path credentialsFile = Path.of(requireValue(config.credentialsFile(), GoogleSheetsConfigStore.ENV_CREDENTIALS_FILE))
            .toAbsolutePath()
            .normalize();
        if (!Files.exists(credentialsFile)) {
            throw new IllegalArgumentException("Credentials file not found: " + credentialsFile);
        }

        String accessToken = fetchAccessToken(credentialsFile);
        Map<String, List<BidSheetRow>> rowsBySheet = groupRowsBySheetName(rows, fallbackSheetName);
        List<SheetOverview> sheetOverviews = new ArrayList<>();
        for (Map.Entry<String, List<BidSheetRow>> entry : rowsBySheet.entrySet()) {
            String sheetName = entry.getKey();
            List<BidSheetRow> sheetRows = entry.getValue();

            ensureSheetExists(spreadsheetId, sheetName, accessToken);
            clearDataRange(spreadsheetId, sheetName, accessToken);
            updateDataRange(spreadsheetId, sheetName, sheetRows, accessToken);
            applyTablePresentation(spreadsheetId, sheetName, Math.max(1, sheetRows.size() + 1), accessToken);
            upsertStatusDataValidation(spreadsheetId, sheetName, accessToken);
            upsertStatusConditionalFormatRules(spreadsheetId, sheetName, accessToken);
            upsertRemainingConditionalFormatRule(spreadsheetId, sheetName, accessToken);

            Integer sheetId = resolveSheetId(spreadsheetId, sheetName, accessToken);
            String navigationLink = sheetId == null
                ? ""
                : "https://docs.google.com/spreadsheets/d/" + spreadsheetId + "/edit#gid=" + sheetId;
            sheetOverviews.add(new SheetOverview(
                sheetName,
                sheetRows.size(),
                buildStatusSummary(sheetRows),
                navigationLink
            ));
        }

        syncIndexSheet(spreadsheetId, accessToken, sheetOverviews);
    }

    private static Map<String, List<BidSheetRow>> groupRowsBySheetName(List<BidSheetRow> rows, String fallbackSheetName) {
        LinkedHashMap<String, List<BidSheetRow>> grouped = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            String sheetName = sanitizeSheetTitle(fallbackSheetName);
            grouped.put(sheetName, List.of());
            return grouped;
        }

        for (BidSheetRow row : rows) {
            String parentFolderName = extractParentFolderName(row, fallbackSheetName);
            String sheetName = sanitizeSheetTitle(parentFolderName);
            grouped.computeIfAbsent(sheetName, key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static String extractParentFolderName(BidSheetRow row, String fallbackSheetName) {
        if (row == null) {
            return defaultIfBlank(fallbackSheetName, DEFAULT_SHEET_NAME);
        }

        String parentFolderName = normalizeForSort(row.parentFolderName());
        if (parentFolderName != null) {
            return parentFolderName;
        }

        String folderLink = normalizeForSort(row.folderLink());
        if (folderLink != null) {
            try {
                Path folderPath = Path.of(folderLink).toAbsolutePath().normalize();
                Path parent = folderPath.getParent();
                if (parent != null && parent.getFileName() != null) {
                    String name = normalizeForSort(parent.getFileName().toString());
                    if (name != null) {
                        return name;
                    }
                }
            } catch (Exception ignored) {
                // fallback to manual path parsing below
            }

            String normalizedPath = folderLink.replace('\\', '/');
            String[] segments = normalizedPath.split("/+");
            if (segments.length >= 2) {
                String name = normalizeForSort(segments[segments.length - 2]);
                if (name != null) {
                    return name;
                }
            }
        }

        return defaultIfBlank(fallbackSheetName, DEFAULT_SHEET_NAME);
    }

    private static String sanitizeSheetTitle(String rawSheetName) {
        String normalized = defaultIfBlank(rawSheetName, DEFAULT_SHEET_NAME)
            .replaceAll("[\\\\/:?*\\[\\]]", " ")
            .replaceAll("\\s+", " ")
            .trim();

        if (normalized.isEmpty()) {
            normalized = DEFAULT_SHEET_NAME;
        }
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 100).trim();
        }
        normalized = normalized.isEmpty() ? DEFAULT_SHEET_NAME : normalized;
        if (normalized.equalsIgnoreCase(INDEX_SHEET_NAME)) {
            normalized = INDEX_SHEET_NAME + "_Data";
        }
        return normalized;
    }

    private static void syncIndexSheet(String spreadsheetId, String accessToken, List<SheetOverview> sheetOverviews) throws Exception {
        List<SheetOverview> sortedOverviews = sortSheetOverviews(sheetOverviews);
        ensureSheetExists(spreadsheetId, INDEX_SHEET_NAME, accessToken);
        clearDataRange(spreadsheetId, INDEX_SHEET_NAME, accessToken);
        updateSingleRange(
            spreadsheetId,
            toSheetA1Prefix(INDEX_SHEET_NAME) + "!" + INDEX_DATA_RANGE_START_A1,
            buildIndexValues(sortedOverviews),
            accessToken,
            "USER_ENTERED"
        );
        applyIndexSheetPresentation(spreadsheetId, accessToken, Math.max(1, sortedOverviews.size() + 1), sortedOverviews);
    }

    private static List<List<Object>> buildIndexValues(List<SheetOverview> sheetOverviews) {
        String updatedAt = LocalDateTime.now().format(INDEX_TIME_FORMAT);
        List<List<Object>> values = new ArrayList<>();
        values.add(new ArrayList<>(INDEX_HEADERS));

        int stt = 1;
        for (SheetOverview overview : sheetOverviews) {
            values.add(List.of(
                Integer.toString(stt),
                safeText(overview.sheetName()),
                Integer.toString(Math.max(0, overview.totalRows())),
                safeText(overview.statusSummary()),
                updatedAt,
                overview.navigationLink() == null || overview.navigationLink().isBlank() ? "" : "Mo sheet"
            ));
            stt++;
        }
        return values;
    }

    private static List<SheetOverview> sortSheetOverviews(List<SheetOverview> sheetOverviews) {
        List<SheetOverview> sorted = new ArrayList<>(sheetOverviews == null ? List.of() : sheetOverviews);
        sorted.sort(Comparator.comparing(item -> item.sheetName(), String::compareToIgnoreCase));
        return sorted;
    }

    private static String buildStatusSummary(List<BidSheetRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "Không có dữ liệu";
        }

        LinkedHashMap<String, Integer> statusCounts = new LinkedHashMap<>();
        statusCounts.put("Mời thầu", 0);
        statusCounts.put("Đã đóng thầu", 0);
        statusCounts.put("Mở thầu", 0);
        statusCounts.put("Có KQ chọn nhà thầu", 0);
        statusCounts.put("Có thông tin hợp đồng", 0);

        int emptyStatusCount = 0;
        for (BidSheetRow row : rows) {
            String label = toStatusLabel(row == null ? null : row.status());
            if (label == null || label.isBlank()) {
                emptyStatusCount++;
                continue;
            }
            statusCounts.merge(label, 1, Integer::sum);
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
            if (entry.getValue() > 0) {
                parts.add(entry.getKey() + ": " + entry.getValue());
            }
        }
        if (emptyStatusCount > 0) {
            parts.add("Chưa rõ: " + emptyStatusCount);
        }
        return parts.isEmpty() ? "Không có trạng thái" : String.join(" | ", parts);
    }

    private static void applyIndexSheetPresentation(
        String spreadsheetId,
        String accessToken,
        int rowCount,
        List<SheetOverview> sheetOverviews
    ) throws Exception {
        Integer sheetId = resolveSheetId(spreadsheetId, INDEX_SHEET_NAME, accessToken);
        if (sheetId == null) {
            return;
        }

        List<Map<String, Object>> requests = new ArrayList<>();
        requests.add(Map.of(
            "updateSheetProperties", Map.of(
                "properties", Map.of(
                    "sheetId", sheetId,
                    "gridProperties", Map.of("frozenRowCount", 1)
                ),
                "fields", "gridProperties.frozenRowCount"
            )
        ));
        requests.add(columnWidthRequest(sheetId, 0, 70));
        requests.add(columnWidthRequest(sheetId, 1, 300));
        requests.add(columnWidthRequest(sheetId, 2, 165));
        requests.add(columnWidthRequest(sheetId, 3, 500));
        requests.add(columnWidthRequest(sheetId, 4, 210));
        requests.add(columnWidthRequest(sheetId, 5, 250));

        requests.add(Map.of(
            "setBasicFilter", Map.of(
                "filter", Map.of(
                    "range", Map.of(
                        "sheetId", sheetId,
                        "startRowIndex", 0,
                        "endRowIndex", rowCount,
                        "startColumnIndex", 0,
                        "endColumnIndex", 6
                    )
                )
            )
        ));

        requests.add(Map.of(
            "repeatCell", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 0,
                    "endRowIndex", 1,
                    "startColumnIndex", 0,
                    "endColumnIndex", 6
                ),
                "cell", Map.of(
                    "userEnteredFormat", Map.of(
                        "backgroundColor", color(0.87, 0.92, 0.98),
                        "textFormat", Map.of("bold", true),
                        "horizontalAlignment", "CENTER",
                        "verticalAlignment", "MIDDLE",
                        "wrapStrategy", "WRAP"
                    )
                ),
                "fields", "userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment,wrapStrategy)"
            )
        ));

        requests.add(Map.of(
            "repeatCell", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 1,
                    "endRowIndex", rowCount,
                    "startColumnIndex", 0,
                    "endColumnIndex", 6
                ),
                "cell", Map.of(
                    "userEnteredFormat", Map.of(
                        "verticalAlignment", "MIDDLE",
                        "wrapStrategy", "WRAP"
                    )
                ),
                "fields", "userEnteredFormat(verticalAlignment,wrapStrategy)"
            )
        ));

        requests.add(Map.of(
            "repeatCell", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 1,
                    "endRowIndex", rowCount,
                    "startColumnIndex", 0,
                    "endColumnIndex", 1
                ),
                "cell", Map.of(
                    "userEnteredFormat", Map.of(
                        "horizontalAlignment", "CENTER"
                    )
                ),
                "fields", "userEnteredFormat.horizontalAlignment"
            )
        ));

        requests.add(Map.of(
            "repeatCell", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 1,
                    "endRowIndex", rowCount,
                    "startColumnIndex", 2,
                    "endColumnIndex", 3
                ),
                "cell", Map.of(
                    "userEnteredFormat", Map.of(
                        "horizontalAlignment", "CENTER"
                    )
                ),
                "fields", "userEnteredFormat.horizontalAlignment"
            )
        ));

        requests.add(Map.of(
            "repeatCell", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 1,
                    "endRowIndex", rowCount,
                    "startColumnIndex", 4,
                    "endColumnIndex", 5
                ),
                "cell", Map.of(
                    "userEnteredFormat", Map.of(
                        "horizontalAlignment", "CENTER"
                    )
                ),
                "fields", "userEnteredFormat.horizontalAlignment"
            )
        ));

        requests.add(Map.of(
            "addBanding", Map.of(
                "bandedRange", Map.of(
                    "range", Map.of(
                        "sheetId", sheetId,
                        "startRowIndex", 0,
                        "endRowIndex", rowCount,
                        "startColumnIndex", 0,
                        "endColumnIndex", 6
                    ),
                    "rowProperties", Map.of(
                        "headerColor", color(0.87, 0.92, 0.98),
                        "firstBandColor", color(1.0, 1.0, 1.0),
                        "secondBandColor", color(0.97, 0.98, 1.0)
                    )
                )
            )
        ));
        requests.add(Map.of(
            "updateBorders", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 0,
                    "endRowIndex", rowCount,
                    "startColumnIndex", 0,
                    "endColumnIndex", 6
                ),
                "top", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "bottom", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "left", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "right", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "innerHorizontal", Map.of("style", "SOLID", "width", 1, "color", color(0.88, 0.90, 0.92)),
                "innerVertical", Map.of("style", "SOLID", "width", 1, "color", color(0.88, 0.90, 0.92))
            )
        ));

        addIndexNavigationLinkRequests(requests, sheetId, sheetOverviews);

        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId + ":batchUpdate";
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of("requests", requests));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "apply index sheet presentation");
    }

    private static void addIndexNavigationLinkRequests(
        List<Map<String, Object>> requests,
        int sheetId,
        List<SheetOverview> sheetOverviews
    ) {
        if (sheetOverviews == null || sheetOverviews.isEmpty()) {
            return;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SheetOverview overview : sheetOverviews) {
            String navigationLink = safeText(overview.navigationLink()).trim();
            Map<String, Object> cell = navigationLink.isEmpty()
                ? Map.of("userEnteredValue", Map.of("stringValue", ""))
                : Map.of(
                    "userEnteredValue", Map.of("stringValue", "Mo sheet"),
                    "userEnteredFormat", Map.of(
                        "textFormat", Map.of(
                            "foregroundColor", color(0.10, 0.33, 0.88),
                            "underline", true,
                            "link", Map.of("uri", navigationLink)
                        )
                    )
                );
            rows.add(Map.of("values", List.of(cell)));
        }

        requests.add(Map.of(
            "updateCells", Map.of(
                "start", Map.of(
                    "sheetId", sheetId,
                    "rowIndex", 1,
                    "columnIndex", 5
                ),
                "rows", rows,
                "fields", "userEnteredValue,userEnteredFormat.textFormat"
            )
        ));
    }

    private record SheetOverview(
        String sheetName,
        int totalRows,
        String statusSummary,
        String navigationLink
    ) {
    }

    private static String requireValue(String value, String sourceName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Google Sheets config: " + sourceName);
        }
        return value.trim();
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static String fetchAccessToken(Path credentialsFile) throws IOException {
        try (InputStream in = Files.newInputStream(credentialsFile)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                .createScoped(List.of(
                    "https://www.googleapis.com/auth/spreadsheets",
                    "https://www.googleapis.com/auth/drive.file"
                ));
            credentials.refreshIfExpired();
            AccessToken token = credentials.getAccessToken();
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                throw new IllegalStateException("Unable to obtain Google access token.");
            }
            return token.getTokenValue();
        }
    }

    private static void clearDataRange(String spreadsheetId, String sheetName, String accessToken) throws Exception {
        String sheetPrefix = toSheetA1Prefix(sheetName);
        clearSingleRange(spreadsheetId, sheetPrefix, accessToken);
    }

    private static void ensureSheetExists(String spreadsheetId, String sheetName, String accessToken) throws Exception {
        if (sheetExists(spreadsheetId, sheetName, accessToken)) {
            return;
        }
        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId + ":batchUpdate";
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
            "requests", List.of(
                Map.of(
                    "addSheet", Map.of(
                        "properties", Map.of("title", sheetName)
                    )
                )
            )
        ));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "create sheet " + sheetName);
    }

    private static boolean sheetExists(String spreadsheetId, String sheetName, String accessToken) throws Exception {
        JsonNode root = readSpreadsheetMetadata(spreadsheetId, "sheets(properties(title))", accessToken);
        JsonNode sheets = root == null ? null : root.get("sheets");
        if (sheets == null || !sheets.isArray()) {
            return false;
        }
        for (JsonNode sheet : sheets) {
            String title = sheet.path("properties").path("title").asText("").trim();
            if (sheetName.equals(title)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode readSpreadsheetMetadata(String spreadsheetId, String fields, String accessToken) throws Exception {
        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId + "?fields=" + urlEncode(fields);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "read spreadsheet metadata");
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static void upsertStatusDataValidation(String spreadsheetId, String sheetName, String accessToken)
        throws Exception {
        Integer sheetId = resolveSheetId(spreadsheetId, sheetName, accessToken);
        if (sheetId == null) {
            return;
        }

        List<Map<String, Object>> values = List.of(
            Map.of("userEnteredValue", "Mời thầu"),
            Map.of("userEnteredValue", "Đã đóng thầu"),
            Map.of("userEnteredValue", "Mở thầu"),
            Map.of("userEnteredValue", "Có KQ chọn nhà thầu"),
            Map.of("userEnteredValue", "Có thông tin hợp đồng")
        );

        Map<String, Object> requestBody = Map.of(
            "requests", List.of(
                Map.of(
                    "repeatCell", Map.of(
                        "range", Map.of(
                            "sheetId", sheetId,
                            "startRowIndex", 1,
                            "startColumnIndex", STATUS_COLUMN_INDEX,
                            "endColumnIndex", STATUS_COLUMN_INDEX + 1
                        ),
                        "cell", Map.of(
                            "dataValidation", Map.of(
                                "condition", Map.of(
                                    "type", "ONE_OF_LIST",
                                    "values", values
                                ),
                                "showCustomUi", true,
                                "strict", false
                            )
                        ),
                        "fields", "dataValidation"
                    )
                )
            )
        );

        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId + ":batchUpdate";
        String payload = OBJECT_MAPPER.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "apply status data validation");
    }

    private static Integer resolveSheetId(String spreadsheetId, String sheetName, String accessToken) throws Exception {
        JsonNode root = readSpreadsheetMetadata(spreadsheetId, "sheets(properties(sheetId,title))", accessToken);
        JsonNode sheets = root == null ? null : root.get("sheets");
        if (sheets == null || !sheets.isArray()) {
            return null;
        }
        for (JsonNode sheet : sheets) {
            String title = sheet.path("properties").path("title").asText("").trim();
            if (sheetName.equals(title)) {
                return sheet.path("properties").path("sheetId").asInt();
            }
        }
        return null;
    }

    private static void applyTablePresentation(String spreadsheetId, String sheetName, int rowCount, String accessToken)
        throws Exception {
        JsonNode root = readSpreadsheetMetadata(
            spreadsheetId,
            "sheets(properties(sheetId,title),bandedRanges(bandedRangeId,range))",
            accessToken
        );

        Integer sheetId = null;
        JsonNode targetSheet = null;
        JsonNode sheets = root == null ? null : root.get("sheets");
        if (sheets == null || !sheets.isArray()) {
            return;
        }
        for (JsonNode sheet : sheets) {
            String title = sheet.path("properties").path("title").asText("").trim();
            if (sheetName.equals(title)) {
                sheetId = sheet.path("properties").path("sheetId").asInt();
                targetSheet = sheet;
                break;
            }
        }
        if (sheetId == null || targetSheet == null) {
            return;
        }

        List<Map<String, Object>> requests = new ArrayList<>();
        int dataRowEnd = Math.max(2, rowCount);

        JsonNode bandedRanges = targetSheet.get("bandedRanges");
        if (bandedRanges != null && bandedRanges.isArray()) {
            for (JsonNode bandedRange : bandedRanges) {
                JsonNode range = bandedRange.get("range");
                if (range == null || range.isMissingNode()) {
                    continue;
                }
                int startColumn = range.path("startColumnIndex").asInt(0);
                int endColumn = range.path("endColumnIndex").asInt(0);
                int startRow = range.path("startRowIndex").asInt(0);
                if (startRow == 0 && startColumn == 0 && endColumn == TOTAL_COLUMN_COUNT) {
                    int bandedRangeId = bandedRange.path("bandedRangeId").asInt(-1);
                    if (bandedRangeId > 0) {
                        requests.add(Map.of(
                            "deleteBanding", Map.of("bandedRangeId", bandedRangeId)
                        ));
                    }
                }
            }
        }

        requests.add(Map.of(
            "updateSheetProperties", Map.of(
                "properties", Map.of(
                    "sheetId", sheetId,
                    "gridProperties", Map.of("frozenRowCount", 1)
                ),
                "fields", "gridProperties.frozenRowCount"
            )
        ));

        requests.add(Map.of(
            "setBasicFilter", Map.of(
                "filter", Map.of(
                    "range", Map.of(
                        "sheetId", sheetId,
                        "startRowIndex", 0,
                        "endRowIndex", rowCount,
                        "startColumnIndex", 0,
                        "endColumnIndex", TOTAL_COLUMN_COUNT
                    )
                )
            )
        ));

        requests.add(Map.of(
            "repeatCell", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 0,
                    "endRowIndex", 1,
                    "startColumnIndex", 0,
                    "endColumnIndex", TOTAL_COLUMN_COUNT
                ),
                "cell", Map.of(
                    "userEnteredFormat", Map.of(
                        "backgroundColor", color(0.87, 0.92, 0.98),
                        "textFormat", Map.of("bold", true),
                        "horizontalAlignment", "CENTER",
                        "verticalAlignment", "MIDDLE",
                        "wrapStrategy", "WRAP"
                    )
                ),
                "fields", "userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment,wrapStrategy)"
            )
        ));

        requests.add(Map.of(
            "repeatCell", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 1,
                    "endRowIndex", dataRowEnd,
                    "startColumnIndex", 0,
                    "endColumnIndex", TOTAL_COLUMN_COUNT
                ),
                "cell", Map.of(
                    "userEnteredFormat", Map.of(
                        "wrapStrategy", "WRAP",
                        "verticalAlignment", "MIDDLE"
                    )
                ),
                "fields", "userEnteredFormat(wrapStrategy,verticalAlignment)"
            )
        ));

        requests.add(Map.of(
            "addBanding", Map.of(
                "bandedRange", Map.of(
                    "range", Map.of(
                        "sheetId", sheetId,
                        "startRowIndex", 0,
                        "endRowIndex", rowCount,
                        "startColumnIndex", 0,
                        "endColumnIndex", TOTAL_COLUMN_COUNT
                    ),
                    "rowProperties", Map.of(
                        "headerColor", color(0.87, 0.92, 0.98),
                        "firstBandColor", color(1.0, 1.0, 1.0),
                        "secondBandColor", color(0.97, 0.98, 1.0)
                    )
                )
            )
        ));

        requests.add(Map.of(
            "updateBorders", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 0,
                    "endRowIndex", rowCount,
                    "startColumnIndex", 0,
                    "endColumnIndex", TOTAL_COLUMN_COUNT
                ),
                "top", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "bottom", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "left", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "right", Map.of("style", "SOLID", "width", 1, "color", color(0.80, 0.82, 0.85)),
                "innerHorizontal", Map.of("style", "SOLID", "width", 1, "color", color(0.88, 0.90, 0.92)),
                "innerVertical", Map.of("style", "SOLID", "width", 1, "color", color(0.88, 0.90, 0.92))
            )
        ));

        requests.add(columnWidthRequest(sheetId, 0, 130));
        requests.add(columnWidthRequest(sheetId, 1, 360));
        requests.add(columnWidthRequest(sheetId, 2, 260));
        requests.add(columnWidthRequest(sheetId, 3, 260));
        requests.add(columnWidthRequest(sheetId, 4, 150));
        requests.add(columnWidthRequest(sheetId, 5, 220));
        requests.add(columnWidthRequest(sheetId, 6, 180));
        requests.add(columnWidthRequest(sheetId, 7, 180));
        requests.add(columnWidthRequest(sheetId, 8, 420));

        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId + ":batchUpdate";
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of("requests", requests));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "apply sheet table presentation");
    }

    private static Map<String, Object> columnWidthRequest(int sheetId, int columnIndex, int pixelSize) {
        return Map.of(
            "updateDimensionProperties", Map.of(
                "range", Map.of(
                    "sheetId", sheetId,
                    "dimension", "COLUMNS",
                    "startIndex", columnIndex,
                    "endIndex", columnIndex + 1
                ),
                "properties", Map.of("pixelSize", pixelSize),
                "fields", "pixelSize"
            )
        );
    }

    private static void upsertStatusConditionalFormatRules(String spreadsheetId, String sheetName, String accessToken)
        throws Exception {
        JsonNode root = readSpreadsheetMetadata(
            spreadsheetId,
            "sheets(properties(sheetId,title),conditionalFormats)",
            accessToken
        );

        JsonNode sheets = root == null ? null : root.get("sheets");
        if (sheets == null || !sheets.isArray()) {
            return;
        }

        Integer sheetId = null;
        JsonNode targetSheet = null;
        for (JsonNode sheet : sheets) {
            String title = sheet.path("properties").path("title").asText("").trim();
            if (sheetName.equals(title)) {
                sheetId = sheet.path("properties").path("sheetId").asInt();
                targetSheet = sheet;
                break;
            }
        }
        if (sheetId == null || targetSheet == null) {
            return;
        }

        List<Integer> existingRuleIndexes = findStatusRuleIndexes(targetSheet);
        List<Map<String, Object>> requests = new ArrayList<>();

        for (int i = existingRuleIndexes.size() - 1; i >= 0; i--) {
            int ruleIndex = existingRuleIndexes.get(i);
            requests.add(Map.of(
                "deleteConditionalFormatRule", Map.of(
                    "sheetId", sheetId,
                    "index", ruleIndex
                )
            ));
        }

        List<Map<String, Object>> newRules = buildStatusRules(sheetId);
        for (Map<String, Object> rule : newRules) {
            requests.add(Map.of(
                "addConditionalFormatRule", Map.of(
                    "rule", rule,
                    "index", 0
                )
            ));
        }

        if (requests.isEmpty()) {
            return;
        }

        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId + ":batchUpdate";
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of("requests", requests));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "apply status conditional formatting");
    }

    private static List<Integer> findStatusRuleIndexes(JsonNode sheetNode) {
        List<Integer> indexes = new ArrayList<>();
        JsonNode rules = sheetNode.get("conditionalFormats");
        if (rules == null || !rules.isArray()) {
            return indexes;
        }

        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            JsonNode ranges = rule.get("ranges");
            if (ranges == null || !ranges.isArray()) {
                continue;
            }
            if (targetsColumn(ranges, STATUS_COLUMN_INDEX)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static boolean targetsColumn(JsonNode ranges, int columnIndex) {
        for (JsonNode range : ranges) {
            int startCol = range.path("startColumnIndex").asInt(-1);
            int endCol = range.path("endColumnIndex").asInt(-1);
            int startRow = range.path("startRowIndex").asInt(0);
            if (startCol == columnIndex && endCol == columnIndex + 1 && startRow <= 1) {
                return true;
            }
        }
        return false;
    }

    private static void upsertRemainingConditionalFormatRule(String spreadsheetId, String sheetName, String accessToken)
        throws Exception {
        JsonNode root = readSpreadsheetMetadata(
            spreadsheetId,
            "sheets(properties(sheetId,title),conditionalFormats)",
            accessToken
        );

        JsonNode sheets = root == null ? null : root.get("sheets");
        if (sheets == null || !sheets.isArray()) {
            return;
        }

        Integer sheetId = null;
        JsonNode targetSheet = null;
        for (JsonNode sheet : sheets) {
            String title = sheet.path("properties").path("title").asText("").trim();
            if (sheetName.equals(title)) {
                sheetId = sheet.path("properties").path("sheetId").asInt();
                targetSheet = sheet;
                break;
            }
        }
        if (sheetId == null || targetSheet == null) {
            return;
        }

        List<Integer> existingRuleIndexes = findRuleIndexesByColumn(targetSheet, REMAINING_COLUMN_INDEX);
        List<Map<String, Object>> requests = new ArrayList<>();

        for (int i = existingRuleIndexes.size() - 1; i >= 0; i--) {
            int ruleIndex = existingRuleIndexes.get(i);
            requests.add(Map.of(
                "deleteConditionalFormatRule", Map.of(
                    "sheetId", sheetId,
                    "index", ruleIndex
                )
            ));
        }

        List<Map<String, Object>> remainingRules = buildRemainingRules(sheetId);
        for (Map<String, Object> rule : remainingRules) {
            requests.add(Map.of(
                "addConditionalFormatRule", Map.of(
                    "rule", rule,
                    "index", 0
                )
            ));
        }

        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId + ":batchUpdate";
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of("requests", requests));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "apply remaining-time conditional formatting");
    }

    private static List<Integer> findRuleIndexesByColumn(JsonNode sheetNode, int columnIndex) {
        List<Integer> indexes = new ArrayList<>();
        JsonNode rules = sheetNode.get("conditionalFormats");
        if (rules == null || !rules.isArray()) {
            return indexes;
        }

        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            JsonNode ranges = rule.get("ranges");
            if (ranges == null || !ranges.isArray()) {
                continue;
            }
            if (targetsColumn(ranges, columnIndex)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static List<Map<String, Object>> buildRemainingRules(int sheetId) {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(buildRemainingTextRule(sheetId, "0 ngày"));
        rules.add(buildRemainingTextRule(sheetId, "1 ngày"));
        rules.add(buildRemainingTextRule(sheetId, "2 ngày"));
        rules.add(buildRemainingTextRule(sheetId, "0 ngay"));
        rules.add(buildRemainingTextRule(sheetId, "1 ngay"));
        rules.add(buildRemainingTextRule(sheetId, "2 ngay"));
        return rules;
    }

    private static Map<String, Object> buildRemainingTextRule(int sheetId, String startsWithText) {
        return Map.of(
            "ranges", List.of(
                Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 1,
                    "startColumnIndex", REMAINING_COLUMN_INDEX,
                    "endColumnIndex", REMAINING_COLUMN_INDEX + 1
                )
            ),
            "booleanRule", Map.of(
                "condition", Map.of(
                    "type", "TEXT_STARTS_WITH",
                    "values", List.of(Map.of("userEnteredValue", startsWithText))
                ),
                "format", Map.of(
                    "backgroundColor", color(0.98, 0.89, 0.89),
                    "textFormat", Map.of(
                        "foregroundColor", color(0.72, 0.11, 0.11),
                        "bold", true
                    )
                )
            )
        );
    }

    private static List<Map<String, Object>> buildStatusRules(int sheetId) {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(buildStatusRule(sheetId, "Mời thầu", color(0.97, 0.84, 0.85), null));
        rules.add(buildStatusRule(sheetId, "Đã đóng thầu", color(0.72, 0.11, 0.11), color(1.00, 1.00, 1.00)));
        rules.add(buildStatusRule(sheetId, "Mở thầu", color(0.85, 0.95, 0.85), null));
        rules.add(buildStatusRule(sheetId, "Có KQ chọn nhà thầu", color(0.09, 0.60, 0.24), color(1.00, 1.00, 1.00)));
        rules.add(buildStatusRule(sheetId, "Có thông tin hợp đồng", color(0.11, 0.37, 0.13), color(1.00, 1.00, 1.00)));
        return rules;
    }

    private static Map<String, Object> buildStatusRule(
        int sheetId,
        String statusText,
        Map<String, Object> backgroundColor,
        Map<String, Object> textColor
    ) {
        Map<String, Object> textFormat = textColor == null
            ? Map.of()
            : Map.of("foregroundColor", textColor);

        Map<String, Object> format = textFormat.isEmpty()
            ? Map.of("backgroundColor", backgroundColor)
            : Map.of(
                "backgroundColor", backgroundColor,
                "textFormat", textFormat
            );

        return Map.of(
            "ranges", List.of(
                Map.of(
                    "sheetId", sheetId,
                    "startRowIndex", 1,
                    "startColumnIndex", STATUS_COLUMN_INDEX,
                    "endColumnIndex", STATUS_COLUMN_INDEX + 1
                )
            ),
            "booleanRule", Map.of(
                "condition", Map.of(
                    "type", "TEXT_EQ",
                    "values", List.of(Map.of("userEnteredValue", statusText))
                ),
                "format", format
            )
        );
    }

    private static Map<String, Object> color(double red, double green, double blue) {
        return Map.of(
            "red", red,
            "green", green,
            "blue", blue
        );
    }

    private static void clearSingleRange(String spreadsheetId, String rangeA1, String accessToken) throws Exception {
        String encodedRange = urlEncode(rangeA1);
        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId
            + "/values/" + encodedRange + ":clear";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "clear data range " + rangeA1);
    }

    private static void updateDataRange(String spreadsheetId, String sheetName, List<BidSheetRow> rows, String accessToken)
        throws Exception {
        List<BidSheetRow> sortedRows = sortRows(rows);
        String sheetPrefix = toSheetA1Prefix(sheetName);
        updateSingleRange(spreadsheetId, sheetPrefix + "!" + DATA_RANGE_START_A1, buildValues(sortedRows), accessToken, "RAW");
    }

    private static void updateSingleRange(
        String spreadsheetId,
        String rangeA1,
        List<List<Object>> values,
        String accessToken,
        String valueInputOption
    )
        throws Exception {
        String encodedRange = urlEncode(rangeA1);
        String url = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId
            + "/values/" + encodedRange + "?valueInputOption=" + urlEncode(defaultIfBlank(valueInputOption, "RAW"));

        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
            "range", rangeA1,
            "majorDimension", "ROWS",
            "values", values
        ));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json; charset=UTF-8")
            .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureOk(response, "update range " + rangeA1);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String toSheetA1Prefix(String sheetName) {
        String trimmed = sheetName == null ? "" : sheetName.trim();
        if (trimmed.isEmpty()) {
            return "'" + DEFAULT_SHEET_NAME + "'";
        }
        return "'" + trimmed.replace("'", "''") + "'";
    }

    private static void ensureOk(HttpResponse<String> response, String action) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String body = response.body() == null ? "" : response.body();
        throw new IllegalStateException("Google Sheets API failed to " + action
            + " (HTTP " + statusCode + "): " + body);
    }

    private static List<BidSheetRow> sortRows(List<BidSheetRow> rows) {
        List<BidSheetRow> sortedRows = new ArrayList<>(rows);
        sortedRows.sort(Comparator
            .comparing(
                (BidSheetRow row) -> normalizeForSort(row.contractPerformanceFolder()),
                Comparator.nullsLast(String::compareToIgnoreCase)
            )
            .thenComparing(
                row -> normalizeForSort(row.tmbtNumber()),
                Comparator.nullsLast(String::compareToIgnoreCase)
            ));
        return sortedRows;
    }

    private static List<List<Object>> buildValues(List<BidSheetRow> sortedRows) {
        List<List<Object>> values = new ArrayList<>();
        values.add(new ArrayList<>(OUTPUT_HEADERS));
        for (BidSheetRow row : sortedRows) {
            values.add(List.of(
                safeText(row.tmbtNumber()),
                safeText(row.packageName()),
                safeText(row.procuringEntity()),
                safeText(row.contractPerformanceFolder()),
                safeText(formatEstimatedBudget(row.estimatedBudget())),
                safeText(toStatusLabel(row.status())),
                safeText(formatDateTime(row.bidClosingTime())),
                safeText(formatRemaining(row.remainingTimeToClosing())),
                safeText(row.folderLink())
            ));
        }
        return values;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeForSort(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String formatEstimatedBudget(BigInteger value) {
        if (value == null) {
            return "";
        }
        String digits = value.toString();
        StringBuilder sb = new StringBuilder(digits.length() + (digits.length() / 3));
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

    private static String toStatusLabel(BidStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case INVITATION_OPEN -> "Mời thầu";
            case BIDDING_CLOSED -> "Đã đóng thầu";
            case BID_OPENED -> "Mở thầu";
            case CONTRACTOR_SELECTION_RESULT_AVAILABLE -> "Có KQ chọn nhà thầu";
            case CONTRACT_INFORMATION_AVAILABLE -> "Có thông tin hợp đồng";
        };
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMAT);
    }

    private static String formatRemaining(Duration duration) {
        if (duration == null) {
            return "";
        }
        long totalMinutes = Math.max(0, duration.toMinutes());
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes % (24 * 60)) / 60;
        long minutes = totalMinutes % 60;
        return days + " ngày " + hours + " giờ " + minutes + " phút";
    }
}
