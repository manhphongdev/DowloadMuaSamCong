package vn.muasamcong.downloader.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import vn.muasamcong.downloader.util.Utils;

public final class GoogleSheetsConfigStore {

    private static final Path CONFIG_FILE = Utils.dataFile("google_sheets_config.json");
    private static final Object LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 2;

    public static final String ENV_ENABLED = "MUASAMCONG_GSHEETS_AUTO_SYNC";
    public static final String ENV_SPREADSHEET_ID = "MUASAMCONG_GSHEETS_SPREADSHEET_ID";
    public static final String ENV_SHEET_NAME = "MUASAMCONG_GSHEETS_SHEET_NAME";
    public static final String ENV_CREDENTIALS_FILE = "MUASAMCONG_GSHEETS_CREDENTIALS_FILE";

    private GoogleSheetsConfigStore() {
    }

    public static GoogleSheetsConfig load() {
        synchronized (LOCK) {
            if (!Files.exists(CONFIG_FILE)) {
                return GoogleSheetsConfig.empty();
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(CONFIG_FILE));
                if (root == null || !root.isObject()) {
                    return GoogleSheetsConfig.empty();
                }
                return new GoogleSheetsConfig(
                    root.path("autoSync").asBoolean(false),
                    text(root, "spreadsheetId"),
                    text(root, "sheetName"),
                    text(root, "credentialsFile")
                );
            } catch (IOException ex) {
                throw new RuntimeException("Unable to read Google Sheets config: " + CONFIG_FILE.toAbsolutePath(), ex);
            }
        }
    }

    public static void save(GoogleSheetsConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        synchronized (LOCK) {
            Utils.ensureDirectory(CONFIG_FILE.toAbsolutePath().getParent());
            try {
                GoogleSheetsConfig normalized = config.normalized();
                String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                    "schemaVersion", SCHEMA_VERSION,
                    "autoSync", normalized.autoSync(),
                    "spreadsheetId", normalized.spreadsheetId(),
                    "sheetName", normalized.sheetName(),
                    "credentialsFile", normalized.credentialsFile()
                ));
                Files.writeString(CONFIG_FILE, json + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to save Google Sheets config: " + CONFIG_FILE.toAbsolutePath(), ex);
            }
        }
    }

    public static GoogleSheetsConfig resolve() {
        GoogleSheetsConfig fileConfig = load();

        String envEnabled = System.getenv(ENV_ENABLED);
        String envSpreadsheetId = System.getenv(ENV_SPREADSHEET_ID);
        String envSheetName = System.getenv(ENV_SHEET_NAME);
        String envCredentials = System.getenv(ENV_CREDENTIALS_FILE);

        boolean enabled = envEnabled == null
            ? fileConfig.autoSync()
            : parseBoolean(envEnabled);

        String spreadsheetId = firstNonBlank(envSpreadsheetId, fileConfig.spreadsheetId());
        String sheetName = firstNonBlank(envSheetName, fileConfig.sheetName());
        String credentialsFile = firstNonBlank(envCredentials, fileConfig.credentialsFile());

        return new GoogleSheetsConfig(enabled, spreadsheetId, sheetName, credentialsFile).normalized();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText();
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean parseBoolean(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes") || normalized.equals("on");
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback;
    }

    public record GoogleSheetsConfig(
        boolean autoSync,
        String spreadsheetId,
        String sheetName,
        String credentialsFile
    ) {
        public static GoogleSheetsConfig empty() {
            return new GoogleSheetsConfig(false, null, "Sheet1", null);
        }

        public GoogleSheetsConfig normalized() {
            return new GoogleSheetsConfig(
                autoSync,
                normalize(spreadsheetId),
                normalizeOrDefault(sheetName, "Sheet1"),
                normalize(credentialsFile)
            );
        }

        private static String normalize(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static String normalizeOrDefault(String value, String defaultValue) {
            String normalized = normalize(value);
            return normalized == null ? defaultValue : normalized;
        }
    }
}
