package vn.muasamcong.downloader.app;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Alert;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import vn.muasamcong.downloader.core.DownloadCoordinator;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.export.GoogleSheetsSyncService;
import vn.muasamcong.downloader.store.AutoRunConfigStore;
import vn.muasamcong.downloader.store.FolderSelectionStore;
import vn.muasamcong.downloader.store.GoogleSheetsConfigStore;
import vn.muasamcong.downloader.store.RunStateStore;
import vn.muasamcong.downloader.store.RunHistoryStore;
import vn.muasamcong.downloader.store.UpdateConfigStore;
import vn.muasamcong.downloader.core.DownloadWorker;
import vn.muasamcong.downloader.update.UpdateInfo;
import vn.muasamcong.downloader.update.UpdateService;
import vn.muasamcong.downloader.util.Utils;

public final class DownloaderFxApp extends Application {

    private static final Pattern KEYWORD_PATTERN = Pattern.compile("keyword=\"([^\"]+)\"");
    private static final Pattern DOWNLOADED_FILES_PATTERN = Pattern.compile("Downloaded files:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Path BID_ROWS_JSON_FILE = Utils.dataFile("bid_sheet_rows.json").toAbsolutePath().normalize();
    private static final DateTimeFormatter NEXT_RUN_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private Stage ownerStage;
    private ListView<FolderItem> folderListView;
    private TextField reportField;
    private Spinner<Integer> concurrencySpinner;
    private Button startButton;
    private Button stopButton;
    private Button addFolderButton;
    private Button deleteFolderButton;
    private Button checkAllFoldersButton;
    private Button uncheckAllFoldersButton;
    private Button exportSheetButton;
    private Button openReportButton;
    private Button viewLogButton;
    private Button expandProgressButton;
    private Button clearStateButton;
    private Button sheetsConfigButton;
    private Button saveUpdateConfigButton;
    private Button checkUpdateButton;
    private Button downloadUpdateButton;
    private Button openUpdateFolderButton;
    private CheckBox autoCheckUpdateOnStartupCheckBox;
    private CheckBox autoRunCheckBox;
    private Spinner<Integer> autoRunIntervalSpinner;
    private TextField autoRunSpecificTimeField;
    private RadioButton autoRunIntervalModeRadio;
    private RadioButton autoRunSpecificTimeModeRadio;
    private Button autoRunSaveButton;
    private Label autoRunInfoLabel;
    private Label selectedFoldersLabel;
    private Label currentVersionLabel;
    private Label latestVersionLabel;
    private Label updateStatusLabel;
    private TextField updateMetadataUrlField;
    private TextArea updateNotesArea;
    private TextArea logArea;
    private Label statusLabel;
    private Label loadedLabel;
    private Label successLabel;
    private Label failedLabel;
    private ProgressIndicator progressIndicator;

    private final List<String> fullLogs = new ArrayList<>();
    private final List<String> failedKeywords = new ArrayList<>();
    private final ObservableList<FolderItem> folderItems = FXCollections.observableArrayList();
    private final Map<String, String> keywordDownloadState = new LinkedHashMap<>();
    private final Map<String, String> keywordResultState = new LinkedHashMap<>();
    private int loadedKeywords;
    private int successCount;
    private int failCount;
    private boolean runningState;
    private Task<RunStats> activeTask;
    private String currentReportLink;
    private volatile boolean stopRequestedByUser;
    private volatile boolean stopRequestedByScheduler;
    private volatile boolean autoRestartAfterStop;
    private volatile LocalDateTime nextAutoRunAt;
    private UpdateInfo latestUpdateInfo;
    private ScheduledExecutorService autoRunScheduler;
    private final AtomicBoolean folderChooserOpening = new AtomicBoolean(false);
    private final Map<FolderItem, ChangeListener<Boolean>> folderSelectionListeners = new IdentityHashMap<>();

    @Override
    public void start(Stage stage) {
        ownerStage = stage;
        initComponents();
        setupLayout(stage);
        setupListeners();

        stage.setTitle(AppInfo.APP_NAME);
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.show();

        Platform.runLater(this::checkUpdatesOnStartupIfEnabled);
    }

    private void initComponents() {
        folderListView = new ListView<>(folderItems);
        folderListView.setPrefHeight(170);
        folderListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        folderListView.setPlaceholder(new Label("No folder added yet."));
        folderListView.setCellFactory(list -> new FolderListCell());

        selectedFoldersLabel = new Label("Selected: 0");
        selectedFoldersLabel.getStyleClass().add("folder-count-chip");

        addFolderButton = new Button("Add folders");
        addFolderButton.getStyleClass().add("secondary-button");
        addFolderButton.setOnAction(event -> handleAddFolders());

        deleteFolderButton = new Button("Delete");
        deleteFolderButton.getStyleClass().add("secondary-button");
        deleteFolderButton.setOnAction(event -> handleDeleteFolders());

        checkAllFoldersButton = new Button("Check all");
        checkAllFoldersButton.getStyleClass().add("secondary-button");
        checkAllFoldersButton.setOnAction(event -> handleCheckAllFolders(true));

        uncheckAllFoldersButton = new Button("Uncheck all");
        uncheckAllFoldersButton.getStyleClass().add("secondary-button");
        uncheckAllFoldersButton.setOnAction(event -> handleCheckAllFolders(false));

        autoRunCheckBox = new CheckBox("Enable auto run");
        autoRunIntervalSpinner = new Spinner<>(30, 1440, 30, 30);
        autoRunIntervalSpinner.setEditable(true);
        autoRunIntervalSpinner.setPrefWidth(110);

        autoRunSpecificTimeField = new TextField("08:00");
        autoRunSpecificTimeField.setPromptText("HH:mm");
        autoRunSpecificTimeField.setPrefWidth(90);
        autoRunSpecificTimeField.textProperty().addListener((obs, oldVal, newVal) -> updateAutoRunTimeValidation());

        ToggleGroup autoRunModeGroup = new ToggleGroup();
        autoRunIntervalModeRadio = new RadioButton("Interval");
        autoRunIntervalModeRadio.setToggleGroup(autoRunModeGroup);
        autoRunIntervalModeRadio.setSelected(true);

        autoRunSpecificTimeModeRadio = new RadioButton("Specific time");
        autoRunSpecificTimeModeRadio.setToggleGroup(autoRunModeGroup);

        autoRunIntervalModeRadio.selectedProperty().addListener((obs, oldVal, selected) -> refreshAutoRunModeUi());
        autoRunSpecificTimeModeRadio.selectedProperty().addListener((obs, oldVal, selected) -> refreshAutoRunModeUi());

        autoRunSaveButton = new Button("Save schedule");
        autoRunSaveButton.getStyleClass().add("secondary-button");
        autoRunSaveButton.setOnAction(event -> handleSaveAutoRunConfig());

        autoRunInfoLabel = new Label("Auto run is OFF");
        autoRunInfoLabel.getStyleClass().add("field-hint");

        loadSavedFolders();
        loadAutoRunConfig();

        reportField = new TextField();
        reportField.setPromptText("Google Sheets link will appear here...");
        reportField.setEditable(false);

        concurrencySpinner = new Spinner<>(1, 8, 2);
        concurrencySpinner.setEditable(true);
        concurrencySpinner.setPrefWidth(130);

        startButton = new Button("START DOWNLOAD");
        startButton.getStyleClass().add("primary-button");

        stopButton = new Button("STOP");
        stopButton.getStyleClass().add("danger-button");
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> handleStopDownload());

        openReportButton = new Button("Open");
        openReportButton.getStyleClass().add("secondary-button");
        openReportButton.setDisable(true);
        openReportButton.setOnAction(event -> handleOpenReport());

        exportSheetButton = new Button("Export Sheet");
        exportSheetButton.getStyleClass().add("secondary-button");
        exportSheetButton.setOnAction(event -> handleExportGoogleSheet());

        clearStateButton = new Button("Clear run state");
        clearStateButton.getStyleClass().add("secondary-button");
        clearStateButton.setOnAction(event -> handleClearRunState());

        sheetsConfigButton = new Button("Google Sheets");
        sheetsConfigButton.getStyleClass().add("secondary-button");
        sheetsConfigButton.setOnAction(event -> showGoogleSheetsConfigDialog());

        UpdateConfigStore.UpdateConfig updateConfig = UpdateConfigStore.load().normalized();
        updateMetadataUrlField = new TextField(defaultText(updateConfig.metadataUrl()));
        updateMetadataUrlField.setPromptText("https://.../latest.json");

        autoCheckUpdateOnStartupCheckBox = new CheckBox("Auto check update on startup");
        autoCheckUpdateOnStartupCheckBox.setSelected(updateConfig.autoCheckOnStartup());

        currentVersionLabel = new Label("Current version: " + AppInfo.VERSION);
        currentVersionLabel.getStyleClass().add("field-label");

        latestVersionLabel = new Label("Latest version: -");
        latestVersionLabel.getStyleClass().add("field-label");

        updateStatusLabel = new Label("Configure latest.json URL to check updates.");
        updateStatusLabel.getStyleClass().add("field-hint");

        updateNotesArea = new TextArea();
        updateNotesArea.setEditable(false);
        updateNotesArea.setWrapText(true);
        updateNotesArea.setPrefRowCount(5);
        updateNotesArea.setPromptText("Release notes will appear here...");
        updateNotesArea.getStyleClass().add("update-notes-area");

        saveUpdateConfigButton = new Button("Save URL");
        saveUpdateConfigButton.getStyleClass().add("secondary-button");
        saveUpdateConfigButton.setOnAction(event -> handleSaveUpdateConfig());

        checkUpdateButton = new Button("Check for updates");
        checkUpdateButton.getStyleClass().add("secondary-button");
        checkUpdateButton.setOnAction(event -> handleCheckForUpdates());

        downloadUpdateButton = new Button("Download update");
        downloadUpdateButton.getStyleClass().add("primary-button");
        downloadUpdateButton.setDisable(true);
        downloadUpdateButton.setOnAction(event -> handleDownloadUpdate());

        openUpdateFolderButton = new Button("Open update folder");
        openUpdateFolderButton.getStyleClass().add("secondary-button");
        openUpdateFolderButton.setOnAction(event -> handleOpenUpdateFolder());

        viewLogButton = new Button("View detailed logs");
        viewLogButton.getStyleClass().add("secondary-button");
        viewLogButton.setDisable(true);
        viewLogButton.setOnAction(event -> showLogDetails());

        expandProgressButton = new Button("⤢");
        expandProgressButton.getStyleClass().add("icon-button");
        expandProgressButton.setOnAction(event -> showProgressFullscreen());

        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-idle");

        loadedLabel = new Label();
        loadedLabel.getStyleClass().add("field-label");
        successLabel = new Label();
        successLabel.getStyleClass().add("field-label");
        failedLabel = new Label();
        failedLabel.getStyleClass().add("field-label");

        progressIndicator = new ProgressIndicator();
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);
        progressIndicator.setPrefSize(26, 26);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPromptText("Progress will appear here while running...");
        VBox.setVgrow(logArea, Priority.ALWAYS);
        resetProgressCounters();
        refreshProgressView();
        updateReportFromConfig();
        updateAutoRunInfoLabel();
        refreshAutoRunModeUi();
        updateAutoRunTimeValidation();
    }

    private void setupLayout(Stage stage) {
        VBox headerPanel = buildHeaderPanel();
        VBox folderCard = buildFolderCard();
        VBox controlCard = buildControlCard();
        VBox reportCard = buildReportCard();
        VBox settingsCard = buildSettingsCard();

        Label logTitle = new Label("Progress");
        logTitle.getStyleClass().add("section-title");
        Region logSpacer = new Region();
        HBox.setHgrow(logSpacer, Priority.ALWAYS);
        HBox logHeader = new HBox(10, logTitle, logSpacer, expandProgressButton, viewLogButton);
        logHeader.setAlignment(Pos.CENTER_LEFT);

        HBox statsBar = new HBox(24, loadedLabel, successLabel, failedLabel);
        statsBar.setAlignment(Pos.CENTER_LEFT);

        VBox logCard = new VBox(10, logHeader, statsBar, logArea);
        logCard.getStyleClass().add("card-panel");

        VBox rightColumn = new VBox(12, controlCard);
        rightColumn.getStyleClass().add("side-column");

        SplitPane workspace = new SplitPane(folderCard, rightColumn);
        workspace.getStyleClass().add("workspace-split-pane");
        workspace.setDividerPositions(0.55);
        folderCard.setMaxWidth(Double.MAX_VALUE);
        rightColumn.setMaxWidth(Double.MAX_VALUE);

        VBox downloadContent = new VBox(14, workspace, logCard);
        Tab downloadTab = new Tab("Download", downloadContent);
        downloadTab.setClosable(false);

        VBox reportContent = new VBox(reportCard);
        reportContent.setPadding(new Insets(2, 0, 0, 0));
        VBox.setVgrow(reportCard, Priority.ALWAYS);
        reportCard.setMaxWidth(Double.MAX_VALUE);
        reportCard.setMaxHeight(Double.MAX_VALUE);
        Tab reportTab = new Tab("Report", reportContent);
        reportTab.setClosable(false);

        Tab settingsTab = new Tab("Settings", settingsCard);
        settingsTab.setClosable(false);

        TabPane tabPane = new TabPane(downloadTab, reportTab, settingsTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        VBox mainContent = new VBox(14, headerPanel, tabPane);
        mainContent.setPadding(new Insets(16));
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        BorderPane root = new BorderPane(mainContent);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 940, 640);
        scene.getStylesheets().add(getClass().getResource("/vn/muasamcong/downloader/styles.css").toExternalForm());

        stage.setScene(scene);
    }

    private VBox buildHeaderPanel() {
        Label title = new Label(AppInfo.APP_NAME);
        title.getStyleClass().add("screen-title");

        Label statusCaption = new Label("Status:");
        statusCaption.getStyleClass().add("field-label");
        HBox statusChip = new HBox(8, statusCaption, statusLabel);
        statusChip.getStyleClass().add("status-chip");
        statusChip.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusRow = new HBox(10, statusChip, spacer, progressIndicator);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(10, title, statusRow);
        panel.getStyleClass().add("hero-panel");
        return panel;
    }

    private VBox buildFolderCard() {
        Label title = new Label("Folder scope");
        title.getStyleClass().add("section-title");
        VBox folderSection = buildFolderSection();

        VBox card = new VBox(10, title, folderSection);
        card.getStyleClass().add("card-panel");
        return card;
    }

    private VBox buildControlCard() {
        Label title = new Label("Run controls");
        title.getStyleClass().add("section-title");

        Label concurrencyLabel = new Label("Concurrency:");
        concurrencyLabel.getStyleClass().add("field-label");
        HBox concurrencyBox = new HBox(10, concurrencyLabel, concurrencySpinner);
        concurrencyBox.setAlignment(Pos.CENTER_LEFT);

        HBox topBar = new HBox(
            10,
            concurrencyBox,
            new Region()
        );
        HBox.setHgrow(topBar.getChildren().get(1), Priority.ALWAYS);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        HBox actionButtons = new HBox(10, buttonSpacer, stopButton, startButton);
        actionButtons.setAlignment(Pos.CENTER_LEFT);

        VBox autoRunBar = buildAutoRunBar();

        VBox card = new VBox(12, title, topBar, actionButtons, new Separator(), autoRunBar);
        card.getStyleClass().add("card-panel");
        return card;
    }

    private VBox buildSettingsCard() {
        Label title = new Label("Settings");
        title.getStyleClass().add("section-title");

        Label hint = new Label("Configure integrations and manage local run data.");
        hint.getStyleClass().add("field-hint");

        Label maintenanceTitle = new Label("Maintenance");
        maintenanceTitle.getStyleClass().add("field-label");

        Label maintenanceHint = new Label("Manage Google Sheets connection and reset local run state when needed.");
        maintenanceHint.getStyleClass().add("field-hint");

        HBox actions = new HBox(10, sheetsConfigButton, clearStateButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("settings-actions");

        VBox maintenanceCard = new VBox(8, maintenanceTitle, maintenanceHint, actions);
        maintenanceCard.getStyleClass().add("settings-group");

        VBox updatesCard = buildUpdatesSection();
        updatesCard.getStyleClass().add("settings-group");

        VBox box = new VBox(12, title, hint, maintenanceCard, updatesCard);
        box.getStyleClass().add("card-panel");
        box.getStyleClass().add("settings-root");
        return box;
    }

    private VBox buildUpdatesSection() {
        Label title = new Label("Updates");
        title.getStyleClass().add("section-title");

        Label urlLabel = new Label("latest.json URL");
        urlLabel.getStyleClass().add("field-label");

        HBox urlRow = new HBox(10, urlLabel, updateMetadataUrlField, saveUpdateConfigButton);
        HBox.setHgrow(updateMetadataUrlField, Priority.ALWAYS);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        HBox versionRow = new HBox(18, currentVersionLabel, latestVersionLabel);
        versionRow.setAlignment(Pos.CENTER_LEFT);

        HBox actionRow = new HBox(10, checkUpdateButton, downloadUpdateButton, openUpdateFolderButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(9, title, urlRow, autoCheckUpdateOnStartupCheckBox, versionRow, updateStatusLabel, updateNotesArea, actionRow);
        return box;
    }

    private VBox buildReportCard() {
        Label reportTitle = new Label("Report");
        reportTitle.getStyleClass().add("section-title");

        HBox reportRow = buildReportRow();
        VBox card = new VBox(10, reportTitle, reportRow);
        card.getStyleClass().add("card-panel");
        return card;
    }

    private VBox buildFolderSection() {
        Label label = new Label("Select folders");
        label.getStyleClass().add("field-label");
        label.getStyleClass().add("field-label-strong");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(10, label, titleSpacer, selectedFoldersLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actions = new HBox(8, actionSpacer, checkAllFoldersButton, uncheckAllFoldersButton, deleteFolderButton, addFolderButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, titleRow, folderListView, actions);
        VBox.setVgrow(folderListView, Priority.ALWAYS);
        return box;
    }

    private HBox buildReportRow() {
        VBox left = new VBox(reportField);
        HBox.setHgrow(left, Priority.ALWAYS);

        HBox row = new HBox(10, left, exportSheetButton, openReportButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildAutoRunBar() {
        Label intervalLabel = new Label("Every (minutes)");
        intervalLabel.getStyleClass().add("field-label");

        Label specificTimeLabel = new Label("Run at (HH:mm)");
        specificTimeLabel.getStyleClass().add("field-label");

        HBox modeRow = new HBox(14,
            autoRunCheckBox,
            autoRunIntervalModeRadio,
            autoRunSpecificTimeModeRadio
        );
        modeRow.getStyleClass().add("autorun-row");
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox configRow = new HBox(10,
            intervalLabel,
            autoRunIntervalSpinner,
            specificTimeLabel,
            autoRunSpecificTimeField,
            spacer,
            autoRunSaveButton
        );
        configRow.getStyleClass().add("autorun-row");
        configRow.setAlignment(Pos.CENTER_LEFT);

        HBox infoRow = new HBox(autoRunInfoLabel);
        infoRow.getStyleClass().add("autorun-info-row");
        infoRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, modeRow, configRow, infoRow);
        box.getStyleClass().add("autorun-box");
        return box;
    }

    private void setupListeners() {
        startButton.setOnAction(event -> startDownload(false));
        bindFolderSelectionState();
    }

    private void startDownload(boolean autoTriggered) {
        if (activeTask != null) {
            if (autoTriggered) {
                autoRestartAfterStop = true;
                updateStatus("Auto-run queued", "status-running");
                addDetailLog("Auto run: current cycle is still running. Next cycle will start right after this cycle finishes.");
            }
            return;
        }

        stopRequestedByUser = false;
        stopRequestedByScheduler = false;
        logArea.clear();
        clearRunData();
        refreshProgressView();

        List<Path> rootPaths;
        try {
            rootPaths = requireSelectedPaths();
        } catch (IllegalArgumentException ex) {
            addDetailLog(ex.getMessage());
            updateStatus("Error", "status-error");
            return;
        }

        updateStatus(autoTriggered ? "Running (auto)..." : "Running...", "status-running");
        setRunningState(true);

        Task<RunStats> task = new Task<>() {
            @Override
            protected RunStats call() {
                Utils.setLogSink(DownloaderFxApp.this::handleRuntimeLog);
                try {
                    return DownloadCoordinator.run(
                        rootPaths,
                        concurrencySpinner.getValue(),
                        false
                    );
                } finally {
                    Utils.clearLogSink();
                }
            }
        };

        task.setOnSucceeded(event -> {
            if (task.isCancelled() || stopRequestedByUser || stopRequestedByScheduler) {
                applyStoppedStateMessage();
            } else {
                RunStats stats = task.getValue();
                loadedKeywords = stats.getTotalKeywords();
                successCount = stats.getSuccessCount();
                failCount = stats.getFailCount();
                failedKeywords.clear();
                for (RunStats.FailureRecord failure : stats.getFailures()) {
                    failedKeywords.add(failure.keyword() + " | " + failure.reason());
                }
                addDetailLog(String.format("=== COMPLETED === Success: %d | Failed: %d | Total: %d",
                    successCount, failCount, loadedKeywords));
                updateStatus("Completed", "status-success");
                updateReportFromConfig();
            }
            refreshProgressView();
            finishTask();
        });

        task.setOnFailed(event -> {
            Throwable err = task.getException();
            if (task.isCancelled() || stopRequestedByUser || stopRequestedByScheduler) {
                applyStoppedStateMessage();
            } else if (isAllKeywordsCompletedError(err)) {
                handleAllKeywordsCompletedNotice();
            } else {
                updateStatus("Run failed", "status-error");
                addDetailLog("Error: " + (err != null ? err.getMessage() : "Unknown"));
            }
            refreshProgressView();
            finishTask();
        });

        task.setOnCancelled(event -> {
            applyStoppedStateMessage();
            refreshProgressView();
            finishTask();
        });

        activeTask = task;

        Thread worker = new Thread(task, "Downloader-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean isAllKeywordsCompletedError(Throwable err) {
        if (err == null || err.getMessage() == null) {
            return false;
        }
        String message = err.getMessage().toLowerCase();
        return message.contains("already completed") || message.contains("all selected keywords");
    }

    private void handleAllKeywordsCompletedNotice() {
        updateStatus("All keywords completed", "status-success");
        addDetailLog("All selected keywords have already been downloaded in previous runs.");
        addDetailLog("No pending keyword remains for this run.");
        addDetailLog("If you want to run again, use 'Clear run state' in Settings.");

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(ownerStage);
        alert.setTitle("No pending keywords");
        alert.setHeaderText("All selected keywords are already completed.");
        alert.setContentText("No new data to download.\n\nTo run again from scratch, open Settings and click 'Clear run state'.");
        alert.showAndWait();
    }

    private void handleStopDownload() {
        if (activeTask == null) {
            return;
        }
        stopRequestedByUser = true;
        stopRequestedByScheduler = false;
        autoRestartAfterStop = false;
        stopButton.setDisable(true);
        updateStatus("Stopping...", "status-running");
        addDetailLog("Stop requested...");
        DownloadCoordinator.requestStop();
    }

    private void applyStoppedStateMessage() {
        updateStatus("Stopped", "status-error");
        if (stopRequestedByScheduler) {
            addDetailLog("=== STOPPED FOR SCHEDULED AUTO-RUN CYCLE ===");
        } else {
            addDetailLog("=== STOPPED BY USER REQUEST ===");
        }
    }

    private void handleOpenReport() {
        if (currentReportLink == null || currentReportLink.isBlank()) {
            updateStatus("Report not found", "status-error");
            addDetailLog("Google Sheets link is not configured.");
            return;
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                updateStatus("Open failed", "status-error");
                addDetailLog("Desktop browse action is not supported on this machine.");
                return;
            }
            desktop.browse(URI.create(currentReportLink));
        } catch (IOException ex) {
            updateStatus("Open failed", "status-error");
            addDetailLog("Failed to open report: " + ex.getMessage());
        } catch (Exception ex) {
            updateStatus("Open failed", "status-error");
            addDetailLog("Invalid Google Sheets link: " + ex.getMessage());
        }
    }

    private void handleSaveUpdateConfig() {
        try {
            UpdateConfigStore.save(new UpdateConfigStore.UpdateConfig(
                updateMetadataUrlField.getText(),
                autoCheckUpdateOnStartupCheckBox.isSelected()
            ));
            setUpdateStatus("Update URL saved.", false);
        } catch (Exception ex) {
            setUpdateStatus("Save URL failed: " + ex.getMessage(), true);
        }
    }

    private void handleCheckForUpdates() {
        handleCheckForUpdates(false);
    }

    private void handleCheckForUpdates(boolean silent) {
        String metadataUrl = updateMetadataUrlField.getText();
        if (metadataUrl == null || metadataUrl.isBlank()) {
            if (!silent) {
                setUpdateStatus("latest.json URL is required.", true);
            }
            return;
        }

        setUpdateControlsDisabled(true);
        if (!silent) {
            setUpdateStatus("Checking for updates...", false);
        }
        updateNotesArea.clear();

        Task<UpdateInfo> task = new Task<>() {
            @Override
            protected UpdateInfo call() throws Exception {
                UpdateConfigStore.save(new UpdateConfigStore.UpdateConfig(
                    metadataUrl,
                    autoCheckUpdateOnStartupCheckBox.isSelected()
                ));
                return UpdateService.fetchLatest(metadataUrl);
            }
        };

        task.setOnSucceeded(event -> {
            latestUpdateInfo = task.getValue();
            latestVersionLabel.setText("Latest version: " + latestUpdateInfo.version());
            updateNotesArea.setText(buildUpdateNotes(latestUpdateInfo));
            boolean newer = UpdateService.isNewerVersion(latestUpdateInfo.version(), AppInfo.VERSION);
            downloadUpdateButton.setDisable(!newer);
            if (newer) {
                setUpdateStatus("New version available.", false);
                if (silent) {
                    showUpdateAvailableAlert(latestUpdateInfo);
                }
            } else if (!silent) {
                setUpdateStatus("You are using the latest version.", false);
            }
            setUpdateControlsDisabled(false);
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            latestUpdateInfo = null;
            downloadUpdateButton.setDisable(true);
            if (!silent) {
                setUpdateStatus("Check update failed: " + (ex == null ? "Unknown" : ex.getMessage()), true);
            }
            setUpdateControlsDisabled(false);
        });

        Thread worker = new Thread(task, "Update-Check-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void checkUpdatesOnStartupIfEnabled() {
        try {
            UpdateConfigStore.UpdateConfig config = UpdateConfigStore.load().normalized();
            if (!config.autoCheckOnStartup()) {
                return;
            }
            if (config.metadataUrl() == null || config.metadataUrl().isBlank()) {
                return;
            }
            updateMetadataUrlField.setText(config.metadataUrl());
            autoCheckUpdateOnStartupCheckBox.setSelected(true);
            handleCheckForUpdates(true);
        } catch (Exception ex) {
            addDetailLog("Auto-check update failed: " + ex.getMessage());
        }
    }

    private void showUpdateAvailableAlert(UpdateInfo info) {
        if (info == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(ownerStage);
        alert.setTitle("Update available");
        alert.setHeaderText("New version available: " + info.version());
        alert.setContentText("Open Settings > Updates to review notes and download the update.");
        alert.show();
    }

    private void handleDownloadUpdate() {
        if (latestUpdateInfo == null) {
            setUpdateStatus("Check for updates first.", true);
            return;
        }

        setUpdateControlsDisabled(true);
        setUpdateStatus("Downloading update...", false);

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return UpdateService.downloadUpdate(latestUpdateInfo);
            }
        };

        task.setOnSucceeded(event -> {
            Path downloadedUpdatePath = task.getValue();
            setUpdateStatus("Downloaded: " + downloadedUpdatePath, false);
            setUpdateControlsDisabled(false);
            openDownloadedUpdate(downloadedUpdatePath);
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            setUpdateStatus("Download failed: " + (ex == null ? "Unknown" : ex.getMessage()), true);
            setUpdateControlsDisabled(false);
        });

        Thread worker = new Thread(task, "Update-Download-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleOpenUpdateFolder() {
        try {
            Path folder = UpdateService.updateDirectory();
            Utils.ensureDirectory(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception ex) {
            setUpdateStatus("Open update folder failed: " + ex.getMessage(), true);
        }
    }

    private void openDownloadedUpdate(Path updateFile) {
        if (updateFile == null || !Files.exists(updateFile)) {
            return;
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(updateFile.toFile());
            } else if (updateFile.getParent() != null) {
                desktop.open(updateFile.getParent().toFile());
            }
        } catch (Exception ex) {
            setUpdateStatus("Downloaded, but open failed: " + ex.getMessage(), true);
        }
    }

    private String buildUpdateNotes(UpdateInfo info) {
        StringBuilder builder = new StringBuilder();
        if (info.releaseDate() != null && !info.releaseDate().isBlank()) {
            builder.append("Release date: ").append(info.releaseDate()).append(System.lineSeparator());
        }
        if (info.notes() == null || info.notes().isEmpty()) {
            builder.append("No release notes.");
            return builder.toString();
        }
        builder.append("Notes:").append(System.lineSeparator());
        for (String note : info.notes()) {
            builder.append("- ").append(note == null ? "" : note).append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private void setUpdateControlsDisabled(boolean disabled) {
        saveUpdateConfigButton.setDisable(disabled);
        checkUpdateButton.setDisable(disabled);
        openUpdateFolderButton.setDisable(disabled);
        downloadUpdateButton.setDisable(disabled || latestUpdateInfo == null
            || !UpdateService.isNewerVersion(latestUpdateInfo.version(), AppInfo.VERSION));
    }

    private void setUpdateStatus(String message, boolean error) {
        updateStatusLabel.setText(message == null ? "" : message);
        updateStatusLabel.getStyleClass().removeAll("field-hint", "status-error", "status-success");
        updateStatusLabel.getStyleClass().add(error ? "status-error" : "status-success");
    }

    private void handleExportGoogleSheet() {
        if (!Files.exists(BID_ROWS_JSON_FILE)) {
            updateStatus("Export failed", "status-error");
            addDetailLog("No data found to export: " + BID_ROWS_JSON_FILE);
            return;
        }

        exportSheetButton.setDisable(true);
        updateStatus("Syncing sheet...", "status-running");
        addDetailLog("Exporting data to Google Sheets...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                GoogleSheetsSyncService.syncFromJsonNow(BID_ROWS_JSON_FILE);
                return null;
            }
        };

        task.setOnSucceeded(event -> {
            updateReportFromConfig();
            updateStatus("Sheet exported", "status-success");
            addDetailLog("Google Sheets export completed.");
            exportSheetButton.setDisable(false);
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            updateStatus("Export failed", "status-error");
            addDetailLog("Google Sheets export failed: " + (ex != null ? ex.getMessage() : "Unknown"));
            exportSheetButton.setDisable(false);
        });

        Thread worker = new Thread(task, "Sheet-Export-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleClearRunState() {
        showClearRunStateDialog();
    }

    private void showClearRunStateDialog() {
        List<RunStateStore.StateRecord> records = RunStateStore.loadRecords();
        if (records.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(ownerStage);
            alert.setTitle("Clear run state");
            alert.setHeaderText("No state data found.");
            alert.setContentText("There is no saved run state to clear.");
            alert.showAndWait();
            return;
        }

        ComboBox<String> folderBox = new ComboBox<>();
        folderBox.getItems().add("All folders");
        LinkedHashSet<String> folders = new LinkedHashSet<>();
        for (RunStateStore.StateRecord record : records) {
            String rootFolder = resolveRootFolder(record.folderPath());
            if (rootFolder != null && !rootFolder.isBlank()) {
                folders.add(rootFolder);
            }
        }
        folderBox.getItems().addAll(folders);
        folderBox.getSelectionModel().selectFirst();
        folderBox.setMaxWidth(Double.MAX_VALUE);

        TextField keywordSearchField = new TextField();
        keywordSearchField.setPromptText("Search keyword...");

        ObservableList<ClearStateItem> stateItems = FXCollections.observableArrayList();
        ListView<ClearStateItem> stateListView = new ListView<>(stateItems);
        stateListView.setPrefHeight(380);
        stateListView.setCellFactory(list -> new ClearStateItemCell());

        Runnable refreshList = () -> {
            String selectedFolder = folderBox.getValue();
            String keywordFilter = keywordSearchField.getText() == null ? "" : keywordSearchField.getText().trim().toLowerCase();
            stateItems.clear();
            for (RunStateStore.StateRecord record : RunStateStore.loadRecords()) {
                String rootFolder = resolveRootFolder(record.folderPath());
                boolean matchesFolder = selectedFolder == null
                    || "All folders".equals(selectedFolder)
                    || selectedFolder.equals(rootFolder);
                boolean matchesKeyword = keywordFilter.isBlank()
                    || (record.keyword() != null && record.keyword().toLowerCase().contains(keywordFilter));
                if (matchesFolder && matchesKeyword) {
                    stateItems.add(new ClearStateItem(record));
                }
            }
        };
        refreshList.run();

        folderBox.valueProperty().addListener((obs, oldValue, newValue) -> refreshList.run());
        keywordSearchField.textProperty().addListener((obs, oldValue, newValue) -> refreshList.run());

        Button checkAllButton = new Button("Check all");
        checkAllButton.getStyleClass().add("secondary-button");
        checkAllButton.setOnAction(event -> stateItems.forEach(item -> item.selectedProperty().set(true)));

        Button uncheckAllButton = new Button("Uncheck all");
        uncheckAllButton.getStyleClass().add("secondary-button");
        uncheckAllButton.setOnAction(event -> stateItems.forEach(item -> item.selectedProperty().set(false)));

        Button removeSelectedButton = new Button("Delete selected");
        removeSelectedButton.getStyleClass().add("danger-button");

        Button clearAllButton = new Button("Clear all state");
        clearAllButton.getStyleClass().add("secondary-button");

        Button closeButton = new Button("Close");
        closeButton.getStyleClass().add("secondary-button");

        Stage dialog = new Stage();
        dialog.initOwner(ownerStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Clear run state");

        removeSelectedButton.setOnAction(event -> {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (ClearStateItem item : stateItems) {
                if (item.selectedProperty().get() && item.record() != null) {
                    keys.add(item.record().key());
                }
            }

            if (keys.isEmpty()) {
                updateStatus("No keyword selected", "status-error");
                addDetailLog("Please check one or more keywords to delete from run state.");
                return;
            }

            int removed = RunStateStore.removeByKeys(keys);
            updateStatus("State removed: " + removed, "status-success");
            addDetailLog("Removed " + removed + " state records.");
            refreshList.run();
            if (RunStateStore.loadRecords().isEmpty()) {
                dialog.close();
            }
        });

        clearAllButton.setOnAction(event -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(dialog);
            confirm.setTitle("Clear all run state");
            confirm.setHeaderText("Delete all saved state?");
            confirm.setContentText("This will clear run_state.json, run_history and bid_sheet_rows.json.");
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                return;
            }

            try {
                RunStateStore.clear();
                RunHistoryStore.clear();
                DownloadWorker.clearBidInfoOutput();
                updateReportFromConfig();
                clearRunData();
                refreshProgressView();
                updateStatus("Run state cleared", "status-success");
                addDetailLog("run_state.json, run_history and bid_sheet_rows.json were cleared.");
                dialog.close();
            } catch (Exception ex) {
                updateStatus("Clear state failed", "status-error");
                addDetailLog("Failed to clear run state: " + ex.getMessage());
            }
        });

        closeButton.setOnAction(event -> dialog.close());

        Label folderLabel = new Label("Folder");
        folderLabel.getStyleClass().add("field-label");
        HBox filterRow = new HBox(10, folderLabel, folderBox, keywordSearchField);
        HBox.setHgrow(folderBox, Priority.ALWAYS);
        HBox.setHgrow(keywordSearchField, Priority.ALWAYS);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Filter by root folder and search keyword, then check keywords to remove.");
        hint.getStyleClass().add("field-hint");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, checkAllButton, uncheckAllButton, removeSelectedButton, spacer, clearAllButton, closeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, filterRow, hint, stateListView, actions);
        root.setPadding(new Insets(14));
        VBox.setVgrow(stateListView, Priority.ALWAYS);

        dialog.setScene(new Scene(root, 980, 540));
        dialog.showAndWait();
    }

    private String resolveRootFolder(String keywordFolderPath) {
        if (keywordFolderPath == null || keywordFolderPath.isBlank()) {
            return "(No folder)";
        }
        try {
            Path keywordFolder = Path.of(keywordFolderPath).toAbsolutePath().normalize();
            Path parent = keywordFolder.getParent();
            if (parent != null) {
                return parent.toString();
            }
            return keywordFolder.toString();
        } catch (Exception ex) {
            return keywordFolderPath;
        }
    }

    private void showGoogleSheetsConfigDialog() {
        GoogleSheetsConfigStore.GoogleSheetsConfig current = GoogleSheetsConfigStore.load().normalized();

        CheckBox autoSyncCheck = new CheckBox("Enable auto sync to Google Sheets");
        autoSyncCheck.setSelected(current.autoSync());

        TextField spreadsheetIdField = new TextField(defaultText(current.spreadsheetId()));
        spreadsheetIdField.setPromptText("Spreadsheet ID");

        TextField credentialsField = new TextField(defaultText(current.credentialsFile()));
        credentialsField.setPromptText("Path to service-account JSON");

        Button browseCredentialsButton = new Button("Browse...");
        browseCredentialsButton.getStyleClass().add("secondary-button");
        browseCredentialsButton.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select service account JSON");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
            Path currentPath = toPathOrNull(credentialsField.getText());
            if (currentPath != null) {
                Path parent = currentPath.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    chooser.setInitialDirectory(parent.toFile());
                }
            }
            var selected = chooser.showOpenDialog(ownerStage);
            if (selected != null) {
                credentialsField.setText(selected.toPath().toAbsolutePath().normalize().toString());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Spreadsheet ID"), 0, 0);
        grid.add(spreadsheetIdField, 1, 0);
        grid.add(new Label("Credentials JSON"), 0, 1);
        HBox credentialsRow = new HBox(10, credentialsField, browseCredentialsButton);
        HBox.setHgrow(credentialsField, Priority.ALWAYS);
        grid.add(credentialsRow, 1, 1);
        GridPane.setHgrow(spreadsheetIdField, Priority.ALWAYS);
        GridPane.setHgrow(credentialsRow, Priority.ALWAYS);

        Button saveButton = new Button("Save");
        saveButton.getStyleClass().add("primary-button");
        Button closeButton = new Button("Close");
        closeButton.getStyleClass().add("secondary-button");

        HBox actions = new HBox(10, new Region(), closeButton, saveButton);
        HBox.setHgrow(actions.getChildren().get(0), Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, autoSyncCheck, grid, actions);
        content.setPadding(new Insets(14));

        Stage dialog = new Stage();
        dialog.initOwner(ownerStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Google Sheets configuration");
        dialog.setScene(new Scene(content, 760, 210));

        closeButton.setOnAction(event -> dialog.close());
        saveButton.setOnAction(event -> {
            GoogleSheetsConfigStore.GoogleSheetsConfig config = new GoogleSheetsConfigStore.GoogleSheetsConfig(
                autoSyncCheck.isSelected(),
                spreadsheetIdField.getText(),
                null,
                credentialsField.getText()
            ).normalized();

            if (config.autoSync()) {
                if (config.spreadsheetId() == null || config.spreadsheetId().isBlank()) {
                    updateStatus("Config invalid", "status-error");
                    addDetailLog("Google Sheets config error: Spreadsheet ID is required when auto sync is enabled.");
                    return;
                }
                if (config.credentialsFile() == null || config.credentialsFile().isBlank()) {
                    updateStatus("Config invalid", "status-error");
                    addDetailLog("Google Sheets config error: Credentials JSON path is required when auto sync is enabled.");
                    return;
                }
            }

            try {
                GoogleSheetsConfigStore.save(config);
                updateReportFromConfig();
                updateStatus("Config saved", "status-success");
                addDetailLog("Google Sheets configuration saved.");
                dialog.close();
            } catch (Exception ex) {
                updateStatus("Save config failed", "status-error");
                addDetailLog("Failed to save Google Sheets configuration: " + ex.getMessage());
            }
        });

        dialog.showAndWait();
    }

    private void finishTask() {
        boolean shouldRestart = autoRestartAfterStop;
        activeTask = null;
        autoRestartAfterStop = false;
        stopRequestedByUser = false;
        stopRequestedByScheduler = false;
        setRunningState(false);
        viewLogButton.setDisable(fullLogs.isEmpty());
        if (shouldRestart) {
            addDetailLog("Auto run: starting new cycle now.");
            startDownload(true);
        }
    }

    private void setRunningState(boolean running) {
        runningState = running;
        folderListView.setDisable(running);
        addFolderButton.setDisable(running);
        deleteFolderButton.setDisable(running);
        checkAllFoldersButton.setDisable(running);
        uncheckAllFoldersButton.setDisable(running);
        concurrencySpinner.setDisable(running);
        stopButton.setDisable(!running);
        exportSheetButton.setDisable(running);
        clearStateButton.setDisable(running);
        sheetsConfigButton.setDisable(running);
        autoRunCheckBox.setDisable(running);
        autoRunIntervalModeRadio.setDisable(running);
        autoRunSpecificTimeModeRadio.setDisable(running);
        autoRunSaveButton.setDisable(running);
        viewLogButton.setDisable(running || fullLogs.isEmpty());
        progressIndicator.setVisible(running);
        progressIndicator.setManaged(running);
        refreshAutoRunModeUi();
        refreshStartButtonAvailability();
    }

    private void bindFolderSelectionState() {
        folderItems.addListener((ListChangeListener<FolderItem>) change -> {
            while (change.next()) {
                if (change.wasRemoved()) {
                    for (FolderItem removed : change.getRemoved()) {
                        unregisterFolderItemListener(removed);
                    }
                }
                if (change.wasAdded()) {
                    for (FolderItem added : change.getAddedSubList()) {
                        registerFolderItemListener(added);
                    }
                }
            }
            updateFolderSelectionLabel();
            refreshStartButtonAvailability();
        });

        for (FolderItem item : folderItems) {
            registerFolderItemListener(item);
        }
        updateFolderSelectionLabel();
        refreshStartButtonAvailability();
    }

    private void registerFolderItemListener(FolderItem item) {
        if (item == null || folderSelectionListeners.containsKey(item)) {
            return;
        }
        ChangeListener<Boolean> listener = (obs, oldVal, newVal) -> {
            updateFolderSelectionLabel();
            refreshStartButtonAvailability();
        };
        item.selectedProperty().addListener(listener);
        folderSelectionListeners.put(item, listener);
    }

    private void unregisterFolderItemListener(FolderItem item) {
        if (item == null) {
            return;
        }
        ChangeListener<Boolean> listener = folderSelectionListeners.remove(item);
        if (listener != null) {
            item.selectedProperty().removeListener(listener);
        }
    }

    private void updateFolderSelectionLabel() {
        int total = folderItems.size();
        int selected = countSelectedFolders();
        selectedFoldersLabel.setText("Selected: " + selected + "/" + total);
    }

    private int countSelectedFolders() {
        int selected = 0;
        for (FolderItem item : folderItems) {
            if (item.selectedProperty().get()) {
                selected++;
            }
        }
        return selected;
    }

    private void refreshStartButtonAvailability() {
        boolean hasSelection = countSelectedFolders() > 0;
        startButton.setDisable(runningState || !hasSelection);
    }

    private void loadAutoRunConfig() {
        try {
            AutoRunConfigStore.AutoRunConfig config = AutoRunConfigStore.load().normalized();
            autoRunCheckBox.setSelected(config.enabled());
            autoRunIntervalSpinner.getValueFactory().setValue(config.intervalMinutes());
            autoRunSpecificTimeField.setText(defaultText(config.specificTime()));
        if ("SPECIFIC_TIME".equals(config.mode())) {
            autoRunSpecificTimeModeRadio.setSelected(true);
        } else {
            autoRunIntervalModeRadio.setSelected(true);
        }
        refreshAutoRunModeUi();
        updateAutoRunTimeValidation();
        applyAutoRunSchedule(config.enabled(), config.intervalMinutes(), config.mode(), config.specificTime(), false);
        } catch (Exception ex) {
            addDetailLog("Failed to load auto-run config: " + ex.getMessage());
        }
    }

    private void handleSaveAutoRunConfig() {
        int intervalMinutes = normalizeAutoRunInterval(autoRunIntervalSpinner.getValue());
        autoRunIntervalSpinner.getValueFactory().setValue(intervalMinutes);
        boolean enabled = autoRunCheckBox.isSelected();
        String mode = autoRunSpecificTimeModeRadio.isSelected() ? "SPECIFIC_TIME" : "INTERVAL";
        String specificTime = normalizeSpecificTime(autoRunSpecificTimeField.getText());

        if ("SPECIFIC_TIME".equals(mode) && specificTime == null) {
            updateStatus("Schedule save failed", "status-error");
            addDetailLog("Invalid specific time. Use HH:mm format (e.g. 08:00).");
            updateAutoRunTimeValidation();
            return;
        }

        try {
            applyAutoRunSchedule(enabled, intervalMinutes, mode, specificTime, true);
            updateStatus("Schedule saved", "status-success");
            if (!enabled) {
                addDetailLog("Auto run disabled.");
            } else if ("SPECIFIC_TIME".equals(mode)) {
                addDetailLog("Auto run enabled at specific time: " + specificTime + " (daily).");
            } else {
                addDetailLog("Auto run enabled. Interval: " + intervalMinutes + " minute(s).");
            }
        } catch (Exception ex) {
            updateStatus("Schedule save failed", "status-error");
            addDetailLog("Unable to save auto-run config: " + ex.getMessage());
        }
    }

    private String normalizeSpecificTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String time = value.trim();
        if (!time.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            return null;
        }
        return time;
    }

    private int normalizeAutoRunInterval(Integer value) {
        if (value == null || value <= 0) {
            return 30;
        }
        int clamped = Math.max(30, Math.min(value, 1440));
        int remainder = clamped % 30;
        if (remainder == 0) {
            return clamped;
        }
        int rounded = clamped + (30 - remainder);
        return Math.min(rounded, 1440);
    }

    private void applyAutoRunSchedule(boolean enabled, int intervalMinutes, String mode, String specificTime, boolean persistConfig) {
        int safeInterval = normalizeAutoRunInterval(intervalMinutes);
        String safeMode = "SPECIFIC_TIME".equals(mode) ? "SPECIFIC_TIME" : "INTERVAL";
        String safeSpecificTime = normalizeSpecificTime(specificTime);
        if (persistConfig) {
            AutoRunConfigStore.save(new AutoRunConfigStore.AutoRunConfig(enabled, safeInterval, safeMode, safeSpecificTime));
        }
        restartAutoRunScheduler(enabled, safeInterval, safeMode, safeSpecificTime);
        updateAutoRunInfoLabel();
    }

    private void restartAutoRunScheduler(boolean enabled, int intervalMinutes, String mode, String specificTime) {
        shutdownAutoRunScheduler();
        nextAutoRunAt = null;
        if (!enabled) {
            updateAutoRunInfoLabel();
            return;
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Auto-Run-Scheduler");
            thread.setDaemon(true);
            return thread;
        });

        autoRunScheduler = scheduler;
        scheduleNextAutoRun(intervalMinutes, mode, specificTime);
    }

    private void scheduleNextAutoRun(int intervalMinutes, String mode, String specificTime) {
        ScheduledExecutorService scheduler = autoRunScheduler;
        if (scheduler == null) {
            return;
        }

        LocalDateTime nextRun = calculateNextRun(mode, intervalMinutes, specificTime);
        nextAutoRunAt = nextRun;
        Platform.runLater(this::updateAutoRunInfoLabel);

        long delaySeconds = Math.max(1L, Duration.between(LocalDateTime.now(), nextRun).getSeconds());
        scheduler.schedule(() -> Platform.runLater(() -> {
            if (autoRunScheduler == null || !autoRunCheckBox.isSelected()) {
                return;
            }
            handleAutoRunTick();
            scheduleNextAutoRun(intervalMinutes, mode, specificTime);
        }), delaySeconds, TimeUnit.SECONDS);
    }

    private LocalDateTime calculateNextRun(String mode, int intervalMinutes, String specificTime) {
        LocalDateTime now = LocalDateTime.now();
        if (!"SPECIFIC_TIME".equals(mode)) {
            return now.plusMinutes(intervalMinutes);
        }

        String safeTime = normalizeSpecificTime(specificTime);
        if (safeTime == null) {
            return now.plusMinutes(intervalMinutes);
        }
        LocalTime targetTime = LocalTime.parse(safeTime);
        LocalDateTime todayRun = LocalDate.now().atTime(targetTime);
        if (todayRun.isAfter(now)) {
            return todayRun;
        }
        return LocalDate.now().plusDays(1).atTime(targetTime);
    }

    private void handleAutoRunTick() {
        if (!autoRunCheckBox.isSelected()) {
            return;
        }

        String mode = autoRunSpecificTimeModeRadio.isSelected() ? "SPECIFIC_TIME" : "INTERVAL";
        int intervalMinutes = normalizeAutoRunInterval(autoRunIntervalSpinner.getValue());
        String specificTime = normalizeSpecificTime(autoRunSpecificTimeField.getText());
        if ("SPECIFIC_TIME".equals(mode)) {
            addDetailLog("Auto run: cycle triggered at configured time " + defaultText(specificTime) + ".");
        } else {
            addDetailLog("Auto run: cycle triggered (every " + intervalMinutes + " minute(s)).");
        }

        if (activeTask != null) {
            autoRestartAfterStop = true;
            addDetailLog("Auto run: current cycle still running, queued next cycle (graceful mode).");
            return;
        }

        startDownload(true);
    }

    private void updateAutoRunInfoLabel() {
        boolean enabled = autoRunCheckBox.isSelected();
        if (!enabled) {
            autoRunInfoLabel.setText("Auto run is OFF");
            return;
        }

        String mode = autoRunSpecificTimeModeRadio.isSelected() ? "SPECIFIC_TIME" : "INTERVAL";
        int intervalMinutes = normalizeAutoRunInterval(autoRunIntervalSpinner.getValue());
        String specificTime = normalizeSpecificTime(autoRunSpecificTimeField.getText());

        String scheduleText = "SPECIFIC_TIME".equals(mode)
            ? "Auto run at " + (specificTime == null ? "--:--" : specificTime) + " daily"
            : "Auto run every " + intervalMinutes + " minute(s)";

        String nextRunText = nextAutoRunAt == null
            ? ""
            : " | Next: " + nextAutoRunAt.format(NEXT_RUN_FORMAT);
        autoRunInfoLabel.setText(scheduleText + nextRunText);
    }

    private void refreshAutoRunModeUi() {
        if (runningState) {
            autoRunIntervalSpinner.setDisable(true);
            autoRunSpecificTimeField.setDisable(true);
            updateAutoRunTimeValidation();
            return;
        }
        boolean specificMode = autoRunSpecificTimeModeRadio.isSelected();
        autoRunIntervalSpinner.setDisable(specificMode);
        autoRunSpecificTimeField.setDisable(!specificMode);
        updateAutoRunTimeValidation();
    }

    private void updateAutoRunTimeValidation() {
        boolean specificMode = autoRunSpecificTimeModeRadio.isSelected();
        String specificTime = normalizeSpecificTime(autoRunSpecificTimeField.getText());
        boolean invalid = specificMode && specificTime == null;

        autoRunSpecificTimeField.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), invalid);

        boolean canSave = !runningState && (!specificMode || !invalid);
        autoRunSaveButton.setDisable(!canSave);

        if (invalid) {
            autoRunInfoLabel.setText("Specific time is invalid. Use HH:mm (e.g. 08:00).");
            autoRunInfoLabel.getStyleClass().remove("status-success");
            if (!autoRunInfoLabel.getStyleClass().contains("status-error")) {
                autoRunInfoLabel.getStyleClass().add("status-error");
            }
        } else {
            autoRunInfoLabel.getStyleClass().remove("status-error");
            if (!autoRunInfoLabel.getStyleClass().contains("field-hint")) {
                autoRunInfoLabel.getStyleClass().add("field-hint");
            }
            updateAutoRunInfoLabel();
        }
    }

    private void shutdownAutoRunScheduler() {
        ScheduledExecutorService scheduler = autoRunScheduler;
        autoRunScheduler = null;
        nextAutoRunAt = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void stop() {
        shutdownAutoRunScheduler();
        DownloadCoordinator.requestStop();
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }

    private Path toPathOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private void updateReportFromConfig() {
        GoogleSheetsConfigStore.GoogleSheetsConfig config = GoogleSheetsConfigStore.resolve();
        setReportLink(buildGoogleSheetsLink(config));
    }

    private void setReportLink(String reportLink) {
        currentReportLink = reportLink;
        if (reportLink == null || reportLink.isBlank()) {
            reportField.clear();
            openReportButton.setDisable(true);
            return;
        }
        reportField.setText(reportLink);
        openReportButton.setDisable(false);
    }

    private String buildGoogleSheetsLink(GoogleSheetsConfigStore.GoogleSheetsConfig config) {
        if (config == null || config.spreadsheetId() == null || config.spreadsheetId().isBlank()) {
            return null;
        }
        return "https://docs.google.com/spreadsheets/d/" + config.spreadsheetId() + "/edit";
    }

    private void handleAddFolders() {
        if (!folderChooserOpening.compareAndSet(false, true)) {
            return;
        }

        setFolderActionsDisabled(true);
        updateStatus("Opening folder picker...", "status-running");

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                return openMultipleDirectoriesNative();
            }
        };

        task.setOnSucceeded(event -> {
            try {
                List<String> selectedPaths = task.getValue();
                int added = addFolderPaths(selectedPaths);
                if (added > 0) {
                    saveFolderSelections();
                    updateStatus("Folders added: " + added, "status-success");
                } else if (selectedPaths == null || selectedPaths.isEmpty()) {
                    updateStatus("Add cancelled", "status-idle");
                } else {
                    updateStatus("No new folders", "status-error");
                    addDetailLog("All selected folders already exist.");
                }
            } finally {
                folderChooserOpening.set(false);
                setFolderActionsDisabled(false);
            }
        });

        task.setOnFailed(event -> {
            try {
                Throwable ex = task.getException();
                addDetailLog("Native folder picker failed, opening fallback picker: " + (ex == null ? "Unknown" : ex.getMessage()));
                openFallbackMultiFolderPicker();
            } finally {
                folderChooserOpening.set(false);
                setFolderActionsDisabled(false);
            }
        });

        Thread worker = new Thread(task, "Multi-Folder-Picker");
        worker.setDaemon(true);
        worker.start();
    }

    private void openFallbackMultiFolderPicker() {
        List<String> selectedPaths = openMultipleDirectoriesWithSwing();
        int added = addFolderPaths(selectedPaths);
        if (added > 0) {
            saveFolderSelections();
            updateStatus("Folders added: " + added, "status-success");
        } else if (selectedPaths == null || selectedPaths.isEmpty()) {
            updateStatus("Add cancelled", "status-idle");
        } else {
            updateStatus("No new folders", "status-error");
            addDetailLog("All selected folders already exist.");
        }
    }

    private List<String> openMultipleDirectoriesWithSwing() {
        List<String> selectedPaths = new ArrayList<>();
        Runnable chooserTask = () -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select folders");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setMultiSelectionEnabled(true);
            chooser.setAcceptAllFileFilterUsed(false);

            int result = chooser.showOpenDialog(null);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }

            java.io.File[] selectedFiles = chooser.getSelectedFiles();
            if (selectedFiles == null || selectedFiles.length == 0) {
                java.io.File selectedFile = chooser.getSelectedFile();
                if (selectedFile != null) {
                    selectedFiles = new java.io.File[] { selectedFile };
                }
            }
            if (selectedFiles == null) {
                return;
            }
            for (java.io.File selectedFile : selectedFiles) {
                if (selectedFile != null) {
                    selectedPaths.add(selectedFile.toPath().toAbsolutePath().normalize().toString());
                }
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                chooserTask.run();
            } else {
                SwingUtilities.invokeAndWait(chooserTask);
            }
        } catch (Exception ex) {
            updateStatus("Folder picker failed", "status-error");
            addDetailLog("Fallback folder picker failed: " + ex.getMessage());
        }
        return selectedPaths;
    }

    private void setFolderActionsDisabled(boolean disabled) {
        addFolderButton.setDisable(disabled || runningState);
        deleteFolderButton.setDisable(disabled || runningState);
        checkAllFoldersButton.setDisable(disabled || runningState);
        uncheckAllFoldersButton.setDisable(disabled || runningState);
    }

    private List<String> openMultipleDirectoriesNative() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new UnsupportedOperationException("Native multi-folder picker is only supported on Windows.");
        }

        Path script = Files.createTempFile("muasamcong-folder-picker-", ".ps1");
        Files.writeString(script, windowsMultiFolderPickerScript(), StandardCharsets.UTF_8);
        try {
            Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-STA",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toAbsolutePath().toString()
            )
                .redirectErrorStream(true)
                .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException(output.isBlank() ? "PowerShell folder picker failed." : output.trim());
            }

            List<String> paths = new ArrayList<>();
            for (String line : output.split("\\R")) {
                String path = line.trim();
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
            return paths;
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    private String windowsMultiFolderPickerScript() {
        return """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -TypeDefinition @"
            using System;
            using System.Runtime.InteropServices;

            [Flags]
            public enum FOS : uint {
                FOS_PICKFOLDERS = 0x00000020,
                FOS_FORCEFILESYSTEM = 0x00000040,
                FOS_ALLOWMULTISELECT = 0x00000200,
                FOS_PATHMUSTEXIST = 0x00000800
            }

            [StructLayout(LayoutKind.Sequential, Pack = 4)]
            public struct PROPERTYKEY {
                public Guid fmtid;
                public uint pid;
            }

            [ComImport]
            [Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")]
            public class FileOpenDialogRCW { }

            [ComImport]
            [Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IShellItem {
                void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
                void GetParent(out IShellItem ppsi);
                void GetDisplayName(uint sigdnName, out IntPtr ppszName);
                void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
                void Compare(IShellItem psi, uint hint, out int piOrder);
            }

            [ComImport]
            [Guid("B63EA76D-1F85-456F-A19C-48159EFA858B")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IShellItemArray {
                void BindToHandler(IntPtr pbc, ref Guid rbhid, ref Guid riid, out IntPtr ppvOut);
                void GetPropertyStore(int flags, ref Guid riid, out IntPtr ppv);
                void GetPropertyDescriptionList(ref PROPERTYKEY keyType, ref Guid riid, out IntPtr ppv);
                void GetAttributes(int AttribFlags, uint sfgaoMask, out uint psfgaoAttribs);
                void GetCount(out uint pdwNumItems);
                void GetItemAt(uint dwIndex, out IShellItem ppsi);
            }

            [ComImport]
            [Guid("D57C7288-D4AD-4768-BE02-9D969532D960")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IFileOpenDialog {
                [PreserveSig] int Show(IntPtr parent);
                void SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
                void SetFileTypeIndex(uint iFileType);
                void GetFileTypeIndex(out uint piFileType);
                void Advise(IntPtr pfde, out uint pdwCookie);
                void Unadvise(uint dwCookie);
                void SetOptions(FOS fos);
                void GetOptions(out FOS pfos);
                void SetDefaultFolder(IShellItem psi);
                void SetFolder(IShellItem psi);
                void GetFolder(out IShellItem ppsi);
                void GetCurrentSelection(out IShellItem ppsi);
                void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
                void GetFileName(out IntPtr pszName);
                void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
                void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
                void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
                void GetResult(out IShellItem ppsi);
                void AddPlace(IShellItem psi, int fdap);
                void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
                void Close(int hr);
                void SetClientGuid(ref Guid guid);
                void ClearClientData();
                void SetFilter(IntPtr pFilter);
                void GetResults(out IShellItemArray ppenum);
                void GetSelectedItems(out IShellItemArray ppsai);
            }
            "@

            $dialog = New-Object FileOpenDialogRCW
            $fileDialog = [IFileOpenDialog]$dialog
            $fileDialog.SetTitle("Select folders")
            $fileDialog.SetOptions([FOS]::FOS_PICKFOLDERS -bor [FOS]::FOS_FORCEFILESYSTEM -bor [FOS]::FOS_ALLOWMULTISELECT -bor [FOS]::FOS_PATHMUSTEXIST)
            $hr = $fileDialog.Show([IntPtr]::Zero)
            if ($hr -eq -2147023673) { exit 0 }
            if ($hr -ne 0) { exit 1 }

            $items = $null
            $fileDialog.GetResults([ref]$items)
            $count = 0
            $items.GetCount([ref]$count)
            for ($i = 0; $i -lt $count; $i++) {
                $item = $null
                $items.GetItemAt([uint32]$i, [ref]$item)
                $ptr = [IntPtr]::Zero
                $item.GetDisplayName(0x80058000, [ref]$ptr)
                $path = [Runtime.InteropServices.Marshal]::PtrToStringUni($ptr)
                [Runtime.InteropServices.Marshal]::FreeCoTaskMem($ptr)
                if ($path) { [Console]::WriteLine($path) }
            }
            """;
    }

    private int addFolderPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return 0;
        }

        LinkedHashSet<String> existing = new LinkedHashSet<>();
        for (FolderItem item : folderItems) {
            existing.add(item.path());
        }

        int added = 0;
        for (String rawPath : paths) {
            try {
                String path = Path.of(rawPath).toAbsolutePath().normalize().toString();
                if (existing.add(path)) {
                    folderItems.add(new FolderItem(path));
                    added++;
                }
            } catch (Exception ex) {
                addDetailLog("Invalid folder path skipped: " + rawPath);
            }
        }
        return added;
    }

    private void handleDeleteFolders() {
        List<FolderItem> checkedItems = new ArrayList<>();
        for (FolderItem item : folderItems) {
            if (item.selectedProperty().get()) {
                checkedItems.add(item);
            }
        }

        if (checkedItems.isEmpty()) {
            updateStatus("Select folders", "status-error");
            addDetailLog("Please check one or more folders to delete.");
            return;
        }

        if (checkedItems.size() > 1 && !confirmDeleteFolders(checkedItems.size())) {
            return;
        }

        folderItems.removeAll(checkedItems);
        saveFolderSelections();
        updateStatus("Folders removed", "status-success");
    }

    private boolean confirmDeleteFolders(int count) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(ownerStage);
        alert.setTitle("Confirm delete");
        alert.setHeaderText("Delete selected folders?");
        alert.setContentText("You are deleting " + count + " folders from the list.");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void handleCheckAllFolders(boolean checked) {
        for (FolderItem item : folderItems) {
            item.selectedProperty().set(checked);
        }
    }

    private void loadSavedFolders() {
        try {
            folderItems.clear();
            List<String> savedFolders = FolderSelectionStore.loadFolders();
            for (String folder : savedFolders) {
                folderItems.add(new FolderItem(folder));
            }
        } catch (Exception ex) {
            addDetailLog("Failed to load folder list: " + ex.getMessage());
        }
    }

    private void saveFolderSelections() {
        List<String> values = new ArrayList<>();
        for (FolderItem item : folderItems) {
            values.add(item.path());
        }
        FolderSelectionStore.saveFolders(values);
    }

    private List<Path> requireSelectedPaths() {
        List<String> selectedFolders = new ArrayList<>();
        for (FolderItem item : folderItems) {
            if (item.selectedProperty().get()) {
                selectedFolders.add(item.path());
            }
        }
        if (selectedFolders.isEmpty()) {
            throw new IllegalArgumentException("No folders selected for download.");
        }

        List<Path> result = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (String rawFolder : selectedFolders) {
            Path path = Path.of(rawFolder);
            Path normalized = path.toAbsolutePath().normalize();
            String key = normalized.toString();
            if (!dedup.add(key)) {
                continue;
            }
            if (!Files.exists(normalized)) {
                throw new IllegalArgumentException("Folder not found: " + normalized);
            }
            if (!Files.isDirectory(normalized)) {
                throw new IllegalArgumentException("Path is not a folder: " + normalized);
            }
            result.add(normalized);
        }

        return result;
    }

    private void updateStatus(String text, String styleClass) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll("status-idle", "status-running", "status-success", "status-error");
        statusLabel.getStyleClass().add(styleClass);
    }

    private void handleRuntimeLog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            String normalized = line.trim();
            fullLogs.add(normalized);
            updateProgressFromLog(normalized);
            refreshProgressView();
        });
    }

    private void updateProgressFromLog(String line) {
        String keyword = extractKeyword(line);
        if (keyword != null && line.contains("| status=START |")) {
            keywordResultState.put(keyword, "RUNNING");
            keywordDownloadState.putIfAbsent(keyword, "-");
        }

        if (line.startsWith("Loaded keywords:")) {
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                try {
                    loadedKeywords = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    // ignore malformed line
                }
            }
            return;
        }

        if (line.contains("| status=SUCCESS |")) {
            successCount++;
            if (keyword != null) {
                keywordResultState.put(keyword, "SUCCESS");
            }
            return;
        }

        if (keyword != null && line.contains("Downloaded files:")) {
            Matcher matcher = DOWNLOADED_FILES_PATTERN.matcher(line);
            if (matcher.find()) {
                int downloaded = Integer.parseInt(matcher.group(1));
                int expected = Integer.parseInt(matcher.group(2));
                keywordDownloadState.put(keyword, downloaded + "/" + expected);
                if (downloaded < expected) {
                    keywordResultState.put(keyword, "FAILED");
                }
            }
        }

        if (keyword != null && line.contains("Downloaded files mismatch:")) {
            keywordResultState.put(keyword, "FAILED");
        }

        if (line.contains("Exceeded retry limit")) {
            failCount++;
            if (keyword != null) {
                failedKeywords.add(keyword);
                keywordResultState.put(keyword, "FAILED");
            }
            return;
        }

        if (keyword != null && line.contains("| status=STOP |")) {
            keywordResultState.put(keyword, "STOPPED");
        }
    }

    private void refreshProgressView() {
        loadedLabel.setText("Loaded keywords: " + loadedKeywords);
        successLabel.setText("Success: " + successCount);
        failedLabel.setText("Failed: " + failCount);
        logArea.setText(buildKeywordProgressText());
    }

    private void clearRunData() {
        fullLogs.clear();
        failedKeywords.clear();
        keywordDownloadState.clear();
        keywordResultState.clear();
        resetProgressCounters();
        viewLogButton.setDisable(true);
    }

    private void resetProgressCounters() {
        loadedKeywords = 0;
        successCount = 0;
        failCount = 0;
    }

    private void addDetailLog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        Platform.runLater(() -> fullLogs.add(line.trim()));
    }

    private void showLogDetails() {
        TextArea detailsArea = new TextArea(buildDetailText());
        detailsArea.setEditable(false);
        detailsArea.setWrapText(true);
        detailsArea.setPrefRowCount(28);

        VBox content = new VBox(detailsArea);
        content.setPadding(new Insets(12));
        VBox.setVgrow(detailsArea, Priority.ALWAYS);

        Scene detailScene = new Scene(content, 920, 640);
        Stage detailStage = new Stage();
        detailStage.initOwner(ownerStage);
        detailStage.initModality(Modality.WINDOW_MODAL);
        detailStage.setTitle("Detailed logs");
        detailStage.setScene(detailScene);
        detailStage.show();
    }

    private void showProgressFullscreen() {
        TextArea progressArea = new TextArea(buildKeywordProgressText());
        progressArea.setEditable(false);
        progressArea.setWrapText(false);
        progressArea.setPrefRowCount(28);

        VBox content = new VBox(progressArea);
        content.setPadding(new Insets(12));
        VBox.setVgrow(progressArea, Priority.ALWAYS);

        Scene detailScene = new Scene(content, 980, 680);
        Stage detailStage = new Stage();
        detailStage.initOwner(ownerStage);
        detailStage.initModality(Modality.WINDOW_MODAL);
        detailStage.setTitle("Progress details");
        detailStage.setScene(detailScene);
        detailStage.show();
    }

    private String buildDetailText() {
        StringBuilder builder = new StringBuilder();
        for (String line : fullLogs) {
            builder.append(line).append(System.lineSeparator());
        }

        if (!failedKeywords.isEmpty()) {
            builder.append(System.lineSeparator()).append("Failed keywords:").append(System.lineSeparator());
            Set<String> unique = new LinkedHashSet<>(failedKeywords);
            for (String keyword : unique) {
                builder.append("- ").append(keyword).append(System.lineSeparator());
            }
        }

        if (builder.length() == 0) {
            return "No logs yet.";
        }
        return builder.toString().trim();
    }

    private String buildKeywordProgressText() {
        if (keywordResultState.isEmpty()) {
            return "No keyword progress yet.";
        }

        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : keywordResultState.entrySet()) {
            String keyword = entry.getKey();
            String status = entry.getValue();
            String downloaded = keywordDownloadState.getOrDefault(keyword, "-");
            builder.append(keyword)
                .append(" | Downloaded files: ")
                .append(downloaded)
                .append(" | ")
                .append(status)
                .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private String extractKeyword(String line) {
        Matcher matcher = KEYWORD_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final class FolderItem {
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private String path;

        private FolderItem(String path) {
            this.path = path;
            this.selected.set(true);
        }

        private String path() {
            return path;
        }

        private void path(String value) {
            path = value;
        }

        private BooleanProperty selectedProperty() {
            return selected;
        }
    }

    private static final class FolderListCell extends ListCell<FolderItem> {
        private final CheckBox checkBox = new CheckBox();
        private final Label pathLabel = new Label();
        private final HBox content = new HBox(8, checkBox, pathLabel);
        private FolderItem boundItem;

        private FolderListCell() {
            content.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(pathLabel, Priority.ALWAYS);
            pathLabel.setMaxWidth(Double.MAX_VALUE);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(FolderItem item, boolean empty) {
            super.updateItem(item, empty);

            if (boundItem != null) {
                checkBox.selectedProperty().unbindBidirectional(boundItem.selectedProperty());
                boundItem = null;
            }

            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            boundItem = item;
            checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
            pathLabel.setText(item.path());
            setGraphic(content);
        }
    }

    private static final class ClearStateItem {
        private final RunStateStore.StateRecord record;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);

        private ClearStateItem(RunStateStore.StateRecord record) {
            this.record = record;
        }

        private BooleanProperty selectedProperty() {
            return selected;
        }

        private RunStateStore.StateRecord record() {
            return record;
        }
    }

    private static final class ClearStateItemCell extends ListCell<ClearStateItem> {
        private final CheckBox checkBox = new CheckBox();
        private final Label label = new Label();
        private final HBox content = new HBox(10, checkBox, label);
        private ClearStateItem bound;

        private ClearStateItemCell() {
            content.setAlignment(Pos.CENTER_LEFT);
            label.setWrapText(true);
            HBox.setHgrow(label, Priority.ALWAYS);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(ClearStateItem item, boolean empty) {
            super.updateItem(item, empty);

            if (bound != null) {
                checkBox.selectedProperty().unbindBidirectional(bound.selectedProperty());
                bound = null;
            }

            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            bound = item;
            checkBox.setVisible(true);
            checkBox.setManaged(true);
            checkBox.selectedProperty().bindBidirectional(item.selectedProperty());

            RunStateStore.StateRecord record = item.record();
            label.setText(valueOrDash(record == null ? null : record.keyword()));
            setGraphic(content);
        }

        private String valueOrDash(String value) {
            return (value == null || value.isBlank()) ? "-" : value;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
