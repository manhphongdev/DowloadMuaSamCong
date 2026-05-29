package vn.muasamcong.downloader.app;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.animation.PauseTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Alert;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import vn.muasamcong.downloader.application.monitor.MonitorScheduler;
import vn.muasamcong.downloader.core.AppFeatures;
import vn.muasamcong.downloader.export.EgpAgentFileDownloadService;
import vn.muasamcong.downloader.core.DownloadCoordinator;
import vn.muasamcong.downloader.core.ExecutionGate;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.domain.monitor.MonitorSettings;
import vn.muasamcong.downloader.export.BidSheetApiSyncService;
import vn.muasamcong.downloader.export.GoogleSheetsSyncService;
import vn.muasamcong.downloader.store.ArtifactFingerprintStore;
import vn.muasamcong.downloader.store.BidTrackingRecordStore;
import vn.muasamcong.downloader.store.BrowserProfileConfigStore;
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
    private static final Pattern RUNTIME_LOG_PATTERN = Pattern.compile(
        "^(\\S+)\\s+(START|INFO|WARN|FAIL|SUCCESS|STOP|DONE)\\s*(.*)$"
    );
    private static final Pattern LOADED_KEYWORD_PATTERN = Pattern.compile("^Loaded keyword:\\s+(\\S+)\\s+folder=(.*)$");
    private static final Pattern DOWNLOADED_FILES_PATTERN = Pattern.compile("Downloaded files:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern FOLDER_IN_MESSAGE_PATTERN = Pattern.compile("\\bfolder=([^|]+)");
    private static final Pattern AGENT_FILE_FIELD_PATTERN = Pattern.compile("\\b(file|fileId|folder)=([^|]+)");
    private static final Path BID_ROWS_JSON_FILE = Utils.dataFile("bid_sheet_rows.json").toAbsolutePath().normalize();
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter PROGRESS_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Stage ownerStage;
    private Stage detailProgressStage;
    private ListView<FolderItem> folderListView;
    private TextField reportField;
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
    private Button installUpdateButton;
    private Button openUpdateFolderButton;
    private Button saveBrowserProfileButton;
    private CheckBox autoCheckUpdateOnStartupCheckBox;
    private ComboBox<String> browserProfileComboBox;
    private Button monitorStopButton;
    private Spinner<Integer> monitorIntervalSpinner;
    private Spinner<Integer> agentDownloadConcurrencySpinner;
    private Spinner<Integer> seleniumDownloadConcurrencySpinner;
    private CheckBox bbmtSeleniumDownloadCheckBox;
    private Button monitorRunNowButton;
    private Button monitorSaveButton;
    private MonitorScheduler monitorScheduler;
    private Label selectedFoldersLabel;
    private Label currentVersionLabel;
    private Label latestVersionLabel;
    private Label updateStatusLabel;
    private Label updateDownloadProgressLabel;
    private Label updateInstallProgressLabel;
    private TextField updateMetadataUrlField;
    private TextArea updateNotesArea;
    private TableView<KeywordProgressRow> progressTableView;
    private TableView<KeywordProgressRow> detailProgressTableView;
    private TableView<AgentFileProgressRow> agentFileTableView;
    private TableView<AgentFileProgressRow> detailAgentFileTableView;
    private Label statusLabel;
    private Label loadedLabel;
    private Label runningLabel;
    private Label downloadedFilesLabel;
    private Label successLabel;
    private Label failedLabel;
    private Label currentActivityLabel;
    private Label agentFileTitleLabel;
    private Label agentFileFolderLabel;
    private Label detailAgentFileTitleLabel;
    private Label detailAgentFileFolderLabel;
    private Label detailProgressZoomLabel;
    private VBox detailProgressRoot;
    private Label toastLabel;
    private StackPane toastLayer;
    private PauseTransition toastHideDelay;
    private ProgressIndicator progressIndicator;

    private final List<String> fullLogs = new ArrayList<>();
    private final List<String> failedKeywords = new ArrayList<>();
    private final Set<String> failedKeywordSet = new LinkedHashSet<>();
    private final ObservableList<FolderItem> folderItems = FXCollections.observableArrayList();
    private final ObservableList<KeywordProgressRow> progressRows = FXCollections.observableArrayList();
    private final ObservableList<AgentFileProgressRow> agentFileRows = FXCollections.observableArrayList();
    private final ObservableList<AgentFileProgressRow> detailAgentFileRows = FXCollections.observableArrayList();
    private final List<String> keywordOrder = new ArrayList<>();
    private final Map<String, Integer> keywordIndexState = new LinkedHashMap<>();
    private final Map<String, String> keywordFolderState = new LinkedHashMap<>();
    private final Map<String, String> keywordDownloadState = new LinkedHashMap<>();
    private final Map<String, String> keywordResultState = new LinkedHashMap<>();
    private final Map<String, String> keywordMessageState = new LinkedHashMap<>();
    private final Map<String, String> keywordStartTimeState = new LinkedHashMap<>();
    private final Map<String, String> keywordEndTimeState = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, AgentFileProgressState>> keywordAgentFilesState = new LinkedHashMap<>();
    private String currentActivityText = "Idle";
    private String selectedAgentKeyword;
    private String currentAgentKeyword;
    private int progressZoomPercent = 125;
    private int loadedKeywords;
    private int successCount;
    private int failCount;
    private boolean runningState;
    private Task<RunStats> activeDownloadTask;
    private String currentReportLink;
    private volatile boolean stopRequestedByUser;
    private UpdateInfo latestUpdateInfo;
    private Path downloadedUpdatePath;
    private final AtomicBoolean folderChooserOpening = new AtomicBoolean(false);
    private final Map<FolderItem, ChangeListener<Boolean>> folderSelectionListeners = new IdentityHashMap<>();
    private SystemTraySupport systemTraySupport;

    @Override
    public void start(Stage stage) {
        ownerStage = stage;
        Platform.setImplicitExit(false);
        initComponents();
        setupLayout(stage);
        setupListeners();
        Utils.setLogSink(this::handleRuntimeLog);

        stage.setTitle(AppInfo.APP_NAME);
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setMaximized(true);

        systemTraySupport = SystemTraySupport.install(stage, this::exitApplication, this::addDetailLog);
        if (systemTraySupport != null) {
            systemTraySupport.attachCloseToTray();
        } else {
            stage.setOnCloseRequest(event -> exitApplication());
        }

        stage.show();

        Platform.runLater(this::showInstallResultIfAny);
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
        addFolderButton.getStyleClass().add("outline-primary-button");
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

        loadSavedFolders();

        reportField = new TextField();
        reportField.setPromptText("Google Sheets link will appear here...");
        reportField.setEditable(false);

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

        updateStatusLabel = new Label("");
        updateStatusLabel.getStyleClass().add("field-hint");

        updateDownloadProgressLabel = new Label("Download: -");
        updateDownloadProgressLabel.getStyleClass().add("field-hint");

        updateInstallProgressLabel = new Label("Install: -");
        updateInstallProgressLabel.getStyleClass().add("field-hint");

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

        installUpdateButton = new Button("Install update");
        installUpdateButton.getStyleClass().add("secondary-button");
        installUpdateButton.setDisable(true);
        installUpdateButton.setOnAction(event -> handleInstallUpdate());

        openUpdateFolderButton = new Button("Open update folder");
        openUpdateFolderButton.getStyleClass().add("secondary-button");
        openUpdateFolderButton.setOnAction(event -> handleOpenUpdateFolder());

        browserProfileComboBox = new ComboBox<>();
        browserProfileComboBox.getItems().addAll("Visible (Chrome window)", "Hidden (headless)");
        browserProfileComboBox.setPrefWidth(220);
        loadBrowserProfileConfig();

        saveBrowserProfileButton = new Button("Save browser mode");
        saveBrowserProfileButton.getStyleClass().add("secondary-button");
        saveBrowserProfileButton.setOnAction(event -> handleSaveBrowserProfileConfig());


        viewLogButton = new Button("View app log");
        viewLogButton.getStyleClass().add("secondary-button");
        viewLogButton.setDisable(false);
        viewLogButton.setOnAction(event -> showLogDetails());

        expandProgressButton = new Button("⤢");
        expandProgressButton.getStyleClass().add("icon-button");
        expandProgressButton.setText("Zoom");
        expandProgressButton.setTooltip(new Tooltip("Zoom progress"));
        expandProgressButton.setOnAction(event -> showProgressFullscreen());

        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-idle");

        loadedLabel = new Label();
        loadedLabel.getStyleClass().addAll("badge", "badge-blue");
        runningLabel = new Label();
        runningLabel.getStyleClass().addAll("badge", "badge-amber");
        downloadedFilesLabel = new Label();
        downloadedFilesLabel.getStyleClass().addAll("badge", "badge-purple");
        successLabel = new Label();
        successLabel.getStyleClass().addAll("badge", "badge-green");
        failedLabel = new Label();
        failedLabel.getStyleClass().addAll("badge", "badge-red");
        currentActivityLabel = new Label("Now: Idle");
        currentActivityLabel.getStyleClass().add("activity-label");
        currentActivityLabel.setWrapText(true);

        toastLabel = new Label();
        toastLabel.getStyleClass().add("toast-notification");
        toastLabel.setVisible(false);
        toastLabel.setManaged(false);
        toastLayer = new StackPane(toastLabel);
        toastLayer.setMouseTransparent(true);
        StackPane.setAlignment(toastLabel, Pos.TOP_RIGHT);
        StackPane.setMargin(toastLabel, new Insets(18));

        progressIndicator = new ProgressIndicator();
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);
        progressIndicator.setPrefSize(26, 26);

        progressTableView = createProgressTable(false);
        VBox.setVgrow(progressTableView, Priority.ALWAYS);
        progressTableView.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            selectedAgentKeyword = newRow == null ? null : newRow.keyword();
            if (selectedAgentKeyword != null && !selectedAgentKeyword.isBlank()) {
                selectKeywordInTable(detailProgressTableView, selectedAgentKeyword);
            }
            refreshAgentFileDetail();
            refreshDetailAgentFileDetail();
        });
        agentFileTableView = createAgentFileTable(agentFileRows);
        resetProgressCounters();
        refreshProgressView();
        updateReportFromConfig();

        monitorIntervalSpinner = new Spinner<>(5, 1440, 30, 1);
        monitorIntervalSpinner.setEditable(true);
        monitorIntervalSpinner.setPrefWidth(115);
        agentDownloadConcurrencySpinner = new Spinner<>(
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_MIN,
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_MAX,
            AppFeatures.AGENT_DOWNLOAD_MAX_CONCURRENCY_DEFAULT,
            1
        );
        agentDownloadConcurrencySpinner.setEditable(true);
        agentDownloadConcurrencySpinner.setPrefWidth(80);
        seleniumDownloadConcurrencySpinner = new Spinner<>(
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_MIN,
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_MAX,
            AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT,
            1
        );
        seleniumDownloadConcurrencySpinner.setEditable(true);
        seleniumDownloadConcurrencySpinner.setPrefWidth(80);
        bbmtSeleniumDownloadCheckBox = new CheckBox("Tải BBMT bằng trình duyệt (Selenium)");
        bbmtSeleniumDownloadCheckBox.setSelected(true);
        bbmtSeleniumDownloadCheckBox.setDisable(!AppFeatures.isMonitorBbmtSeleniumEnabled());
        monitorRunNowButton = new Button("Run now");
        monitorRunNowButton.getStyleClass().add("primary-button");
        monitorRunNowButton.setOnAction(event -> startMonitorNow());
        monitorSaveButton = new Button("Save monitor");
        monitorSaveButton.getStyleClass().add("outline-primary-button");
        monitorSaveButton.setOnAction(event -> saveMonitorSettings());
        monitorStopButton = new Button("Stop monitor");
        monitorStopButton.getStyleClass().add("danger-button");
        monitorStopButton.setDisable(true);
        monitorStopButton.setOnAction(event -> handleStopMonitor());
        monitorScheduler = new MonitorScheduler(BID_ROWS_JSON_FILE, this::selectedPathsForDownload);
        monitorScheduler.setOnCycleIdle(() -> Platform.runLater(this::updateSharedUiState));
        loadMonitorSettings();
        applyDownloadDisabledUi();
    }

    private void setupLayout(Stage stage) {
        VBox headerPanel = buildHeaderPanel();
        VBox folderCard = buildFolderCard();
        VBox controlCard = buildControlCard();
        VBox reportCard = buildReportCard();
        VBox settingsCard = buildSettingsCard();

        Label logTitle = new Label("Progress");
        logTitle.getStyleClass().add("section-title");
        
        HBox statsBar = new HBox(12, loadedLabel, runningLabel, downloadedFilesLabel, successLabel, failedLabel);
        statsBar.setAlignment(Pos.CENTER_LEFT);
        
        Region logSpacer = new Region();
        HBox.setHgrow(logSpacer, Priority.ALWAYS);
        
        HBox logHeader = new HBox(20, logTitle, statsBar, logSpacer, viewLogButton, expandProgressButton);
        logHeader.setAlignment(Pos.CENTER_LEFT);

        VBox agentFilePanel = buildAgentFilePanel();
        VBox logCard = new VBox(12, logHeader, currentActivityLabel, progressTableView, agentFilePanel);
        logCard.getStyleClass().add("card-panel");

        VBox rightColumn = new VBox(12, controlCard);
        VBox.setVgrow(controlCard, Priority.ALWAYS);
        rightColumn.getStyleClass().add("side-column");

        SplitPane workspace = new SplitPane(folderCard, rightColumn);
        workspace.getStyleClass().add("workspace-split-pane");
        workspace.setDividerPositions(0.55);
        folderCard.setMaxWidth(Double.MAX_VALUE);
        rightColumn.setMaxWidth(Double.MAX_VALUE);

        VBox downloadContent = new VBox(14, workspace, logCard);
        downloadContent.setMinHeight(650);
        ScrollPane downloadScroll = new ScrollPane(downloadContent);
        downloadScroll.setFitToWidth(true);
        downloadScroll.setFitToHeight(true);
        downloadScroll.setStyle("-fx-background-color: transparent;");
        Tab downloadTab = new Tab("Download", downloadScroll);
        downloadTab.setClosable(false);

        VBox reportContent = new VBox(reportCard);
        reportContent.setPadding(new Insets(2, 0, 0, 0));
        VBox.setVgrow(reportCard, Priority.ALWAYS);
        reportCard.setMaxWidth(Double.MAX_VALUE);
        reportCard.setMaxHeight(Double.MAX_VALUE);
        reportContent.setMinHeight(300);
        ScrollPane reportScroll = new ScrollPane(reportContent);
        reportScroll.setFitToWidth(true);
        reportScroll.setFitToHeight(true);
        reportScroll.setStyle("-fx-background-color: transparent;");
        Tab reportTab = new Tab("Report", reportScroll);
        reportTab.setClosable(false);

        settingsCard.setMinHeight(Region.USE_PREF_SIZE);
        settingsCard.setMaxWidth(Double.MAX_VALUE);
        VBox settingsContent = new VBox(settingsCard);
        settingsContent.setPadding(new Insets(2, 0, 2, 0));
        settingsContent.setFillWidth(true);
        ScrollPane settingsScroll = new ScrollPane(settingsContent);
        settingsScroll.setFitToWidth(true);
        settingsScroll.setFitToHeight(false);
        settingsScroll.setPannable(true);
        settingsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        settingsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        settingsScroll.setStyle("-fx-background-color: transparent;");
        Tab settingsTab = new Tab("Settings", settingsScroll);
        settingsTab.setClosable(false);

        TabPane tabPane = new TabPane(downloadTab, reportTab, settingsTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        VBox mainContent = new VBox(14, headerPanel, tabPane);
        mainContent.setPadding(new Insets(16));
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        BorderPane root = new BorderPane(mainContent);
        root.getStyleClass().add("app-root");

        StackPane rootStack = new StackPane(root, toastLayer);
        rootStack.getStyleClass().add("app-root");

        Scene scene = new Scene(rootStack, 940, 640);
        scene.getStylesheets().add(getClass().getResource("/vn/muasamcong/downloader/styles.css").toExternalForm());

        stage.setScene(scene);
    }

    private VBox buildHeaderPanel() {
        Label title = new Label(AppInfo.APP_NAME + " v" + AppInfo.VERSION);
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

    private VBox buildAgentFilePanel() {
        agentFileTitleLabel = new Label("Files: select a keyword");
        agentFileTitleLabel.getStyleClass().add("section-title-small");
        agentFileFolderLabel = new Label("Folder: -");
        agentFileFolderLabel.getStyleClass().add("field-hint");

        return buildAgentFilePanel(agentFileTitleLabel, agentFileFolderLabel, agentFileTableView, false);
    }

    private VBox buildAgentFilePanel(
        Label titleLabel,
        Label folderLabel,
        TableView<AgentFileProgressRow> tableView,
        boolean expanded
    ) {
        VBox header = new VBox(2, titleLabel, folderLabel);
        VBox panel = new VBox(8, header, tableView);
        panel.getStyleClass().add("agent-file-panel");
        VBox.setVgrow(tableView, expanded ? Priority.ALWAYS : Priority.NEVER);
        return panel;
    }

    private VBox buildFolderCard() {
        Label title = new Label("Folder scope");
        title.getStyleClass().add("section-title");
        VBox folderSection = buildFolderSection();

        VBox card = new VBox(10, title, folderSection);
        VBox.setVgrow(folderSection, Priority.ALWAYS);
        card.getStyleClass().add("card-panel");
        return card;
    }

    private VBox buildControlCard() {
        Label title = new Label("Monitor controls");
        title.getStyleClass().add("section-title");

        VBox monitorBar = buildMonitorBar();

        VBox card = new VBox(12, title, monitorBar);
        VBox.setVgrow(monitorBar, Priority.ALWAYS);
        card.getStyleClass().add("card-panel");
        return card;
    }

    private VBox buildSettingsCard() {
        Label title = new Label("Settings");
        title.getStyleClass().add("section-title");

        Label maintenanceTitle = new Label("Maintenance");
        maintenanceTitle.getStyleClass().add("field-label");

        HBox actions = new HBox(10, sheetsConfigButton, clearStateButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("settings-actions");

        Label browserTitle = new Label("Browser mode");
        browserTitle.getStyleClass().add("field-label");

        HBox browserRow = new HBox(10, browserProfileComboBox, saveBrowserProfileButton);
        browserRow.setAlignment(Pos.CENTER_LEFT);

        VBox maintenanceCard = new VBox(
            8,
            maintenanceTitle,
            actions,
            new Separator(),
            browserTitle,
            browserRow
        );
        maintenanceCard.getStyleClass().add("settings-group");

        VBox updatesCard = buildUpdatesSection();
        updatesCard.getStyleClass().add("settings-group");

        VBox box = new VBox(12, title, maintenanceCard, updatesCard);
        box.getStyleClass().add("card-panel");
        box.getStyleClass().add("settings-root");
        return box;
    }

    private VBox buildUpdatesSection() {
        Label title = new Label("Updates");
        title.getStyleClass().add("section-title");

        Label urlLabel = new Label("latest.json URL");
        urlLabel.getStyleClass().add("field-label");

        HBox urlRow = new HBox(10, updateMetadataUrlField, saveUpdateConfigButton);
        HBox.setHgrow(updateMetadataUrlField, Priority.ALWAYS);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        HBox versionRow = new HBox(18, currentVersionLabel, latestVersionLabel);
        versionRow.setAlignment(Pos.CENTER_LEFT);

        HBox actionRow = new HBox(10, checkUpdateButton, downloadUpdateButton, installUpdateButton, openUpdateFolderButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(
            9,
            title,
            urlLabel,
            urlRow,
            autoCheckUpdateOnStartupCheckBox,
            versionRow,
            updateStatusLabel,
            updateDownloadProgressLabel,
            updateNotesArea,
            actionRow
        );
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
        actions.setAlignment(Pos.CENTER_RIGHT);

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

    private VBox buildMonitorBar() {
        Label intervalLabel = new Label("Cycle interval (minutes)");
        intervalLabel.getStyleClass().add("field-label");
        HBox intervalBox = new HBox(10, intervalLabel, monitorIntervalSpinner);
        intervalBox.setAlignment(Pos.CENTER_LEFT);

        Label agentThreadsLabel = new Label("Số luồng tải file (agent)");
        agentThreadsLabel.getStyleClass().add("field-label");
        HBox agentThreadsBox = new HBox(10, agentThreadsLabel, agentDownloadConcurrencySpinner);
        agentThreadsBox.setAlignment(Pos.CENTER_LEFT);

        Label seleniumThreadsLabel = new Label("Số tab Chrome (Selenium BBMT)");
        seleniumThreadsLabel.getStyleClass().add("field-label");
        HBox seleniumThreadsBox = new HBox(10, seleniumThreadsLabel, seleniumDownloadConcurrencySpinner);
        seleniumThreadsBox.setAlignment(Pos.CENTER_LEFT);

        HBox actions = new HBox(10, monitorRunNowButton, monitorSaveButton, monitorStopButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, intervalBox, agentThreadsBox, seleniumThreadsBox, bbmtSeleniumDownloadCheckBox, actions);
        box.getStyleClass().add("autorun-box");
        return box;
    }

    private void setupListeners() {
        bindFolderSelectionState();
    }

    private void startDownload() {
        if (!AppFeatures.isDownloadEnabled()) {
            return;
        }
        if (activeDownloadTask != null) {
            updateStatus("Download is running", "status-running");
            return;
        }
        if (ExecutionGate.isMonitorBusy()) {
            updateStatus("Monitor is running", "status-running");
            addDetailLog("Download: skipped because monitor cycle is running.");
            return;
        }
        stopRequestedByUser = false;
        clearRunData();
        refreshProgressView();
        List<Path> selectedRoots = selectedPathsForDownload();
        if (selectedRoots.isEmpty()) {
            updateStatus("No folder selected", "status-error");
            addDetailLog("No folder selected.");
            return;
        }

        int concurrency = monitorScheduler == null
            ? AppFeatures.SELENIUM_DOWNLOAD_MAX_CONCURRENCY_DEFAULT
            : monitorScheduler.loadSettings().normalized().seleniumDownloadConcurrency();
        if (!ExecutionGate.tryBeginDownload()) {
            updateStatus("Monitor is running", "status-running");
            addDetailLog("Download: skipped because monitor cycle is running.");
            return;
        }

        updateStatus("Download is running", "status-running");
        addDetailLog("Download: started.");

        Task<RunStats> task = new Task<>() {
            @Override
            protected RunStats call() {
                ensureChromeProfilesForConcurrency(concurrency);
                return DownloadCoordinator.run(selectedRoots, concurrency, false);
            }
        };
        activeDownloadTask = task;
        setDownloadRunningState(true);

        task.setOnSucceeded(event -> {
            RunStats stats = task.getValue();
            boolean wasStopped = stopRequestedByUser;
            if (wasStopped) {
                applyStoppedStateMessage();
                addDetailLog("Bid sheet sync ran for keywords completed before stop.");
            } else {
                updateStatus("Download completed", "status-success");
            }
            addDetailLog("Download" + (wasStopped ? " stopped" : " completed") + ": "
                + (stats == null ? 0 : stats.getSuccessCount()) + " success, "
                + (stats == null ? 0 : stats.getFailCount()) + " failed.");
            updateReportFromConfig();
            finishDownloadTask();
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            if (isAllKeywordsCompletedError(ex)) {
                handleAllKeywordsCompletedNotice();
            } else {
                updateStatus("Download failed", "status-error");
                addDetailLog("Download failed: " + summarizeException(ex));
            }
            finishDownloadTask();
        });

        task.setOnCancelled(event -> {
            applyStoppedStateMessage();
            finishDownloadTask();
        });

        Thread thread = new Thread(task, "Download-Task");
        thread.setDaemon(true);
        thread.start();
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
        addDetailLog("All selected keywords have already been processed successfully in previous runs.");
        addDetailLog("No pending keyword remains for this run.");
        addDetailLog("If you want to run again, use 'Clear run state' in Settings.");
        showNoPendingKeywordsAlert();
    }

    private void showNoPendingKeywordsAlert() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(ownerStage);
        alert.setTitle("No pending keywords");
        alert.setHeaderText("All selected keywords are already completed.");
        alert.setContentText("No pending keyword to process.\n\nTo run again from scratch, open Settings and click 'Clear run state'.");
        alert.showAndWait();
    }

    private void handleStopMonitor() {
        if (activeDownloadTask != null) {
            handleStopDownload();
            return;
        }
        if (monitorScheduler == null || !monitorScheduler.isCycleActive()) {
            return;
        }
        monitorStopButton.setDisable(true);
        updateStatus("Stopping monitor...", "status-running");
        addDetailLog("Monitor: stop requested...");
        monitorScheduler.requestStop();
        DownloadCoordinator.requestStop();
    }

    private void startMonitorNow() {
        if (monitorScheduler == null) {
            return;
        }
        if (monitorScheduler.isCycleActive() || activeDownloadTask != null || ExecutionGate.isDownloadBusy()) {
            updateStatus("Already running", "status-running");
            return;
        }
        try {
            MonitorSettings settings = buildMonitorSettingsFromUi();
            monitorIntervalSpinner.getValueFactory().setValue(settings.intervalMinutes());
            agentDownloadConcurrencySpinner.getValueFactory().setValue(settings.agentDownloadConcurrency());
            seleniumDownloadConcurrencySpinner.getValueFactory().setValue(settings.seleniumDownloadConcurrency());
            applyAgentDownloadConcurrency(settings.agentDownloadConcurrency());
            monitorScheduler.saveSettings(settings);
        } catch (Exception ex) {
            updateStatus("Monitor config invalid", "status-error");
            addDetailLog("Unable to save monitor settings before run: " + ex.getMessage());
            return;
        }

        clearRunData();
        refreshProgressView();
        updateStatus("Monitor is running", "status-running");
        addDetailLog("Monitor: run now started.");
        setDownloadRunningState(true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                monitorScheduler.runOnce();
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            int unfinishedAgentFiles = countUnfinishedAgentFiles();
            if (unfinishedAgentFiles > 0) {
                updateStatus("Monitor incomplete", "status-error");
                addDetailLog("Monitor: run now completed with " + unfinishedAgentFiles
                    + " agent file(s) not downloaded yet.");
            } else {
                updateStatus("Monitor completed", "status-success");
                addDetailLog("Monitor: run now completed.");
            }
            updateReportFromConfig();
            setDownloadRunningState(false);
        });
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            updateStatus("Monitor failed", "status-error");
            addDetailLog("Monitor run failed: " + summarizeException(ex));
            setDownloadRunningState(false);
        });
        task.setOnCancelled(event -> {
            applyStoppedStateMessage();
            setDownloadRunningState(false);
        });

        Thread thread = new Thread(task, "Monitor-RunNow");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleStopDownload() {
        if (activeDownloadTask == null) {
            return;
        }
        stopRequestedByUser = true;
        if (monitorStopButton != null) {
            monitorStopButton.setDisable(true);
        }
        updateStatus("Stopping download...", "status-running");
        addDetailLog("Download stop requested... (will export sheet when workers finish)");
        DownloadCoordinator.requestStop();
    }

    private void applyStoppedStateMessage() {
        updateStatus("Stopped", "status-error");
        addDetailLog("=== STOPPED BY USER REQUEST ===");
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
        updateDownloadProgressLabel.setText("Download: -");
        updateInstallProgressLabel.setText("Install: -");
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
            downloadedUpdatePath = null;
            latestVersionLabel.setText("Latest version: " + latestUpdateInfo.version());
            updateNotesArea.setText(buildUpdateNotes(latestUpdateInfo));
            boolean newer = UpdateService.isNewerVersion(latestUpdateInfo.version(), AppInfo.VERSION);
            downloadUpdateButton.setDisable(!newer);
            installUpdateButton.setDisable(true);
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
            downloadedUpdatePath = null;
            downloadUpdateButton.setDisable(true);
            installUpdateButton.setDisable(true);
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
                updateMessage("Download: starting...");
                return UpdateService.downloadUpdate(latestUpdateInfo, (downloaded, total) -> {
                    if (total > 0) {
                        double percent = (downloaded * 100.0) / total;
                        updateMessage(String.format(
                            "Download: %.1f%% (%s / %s)",
                            percent,
                            humanBytes(downloaded),
                            humanBytes(total)
                        ));
                    } else {
                        updateMessage("Download: " + humanBytes(downloaded));
                    }
                });
            }
        };

        task.messageProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isBlank()) {
                updateDownloadProgressLabel.setText(newVal);
            }
        });

        task.setOnSucceeded(event -> {
            Path downloadedUpdatePath = task.getValue();
            this.downloadedUpdatePath = downloadedUpdatePath;
            setUpdateStatus("Downloaded. Click Install update to apply.", false);
            updateDownloadProgressLabel.setText("Download: 100% (" + humanBytesSafe(downloadedUpdatePath) + ")");
            setUpdateControlsDisabled(false);
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            setUpdateStatus("Download failed: " + (ex == null ? "Unknown" : ex.getMessage()), true);
            updateDownloadProgressLabel.setText("Download: failed");
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

    private void handleInstallUpdate() {
        if (downloadedUpdatePath == null || !Files.exists(downloadedUpdatePath)) {
            setUpdateStatus("Download update first.", true);
            return;
        }

        try {
            updateInstallProgressLabel.setText("Install: 25% - preparing installer");
            Path appDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path launcherFile = appDir.resolve("MuaSamCong Downloader.exe");
            if (!Files.exists(launcherFile)) {
                setUpdateStatus("Install is supported only in packaged app folder.", true);
                return;
            }
            Path updaterDir = Path.of(System.getProperty("java.io.tmpdir"), "muasamcong-updater")
                .toAbsolutePath()
                .normalize();
            Utils.ensureDirectory(updaterDir);

            Path packageCopyPath = updaterDir.resolve("update-package-" + System.currentTimeMillis() + ".zip");
            Files.copy(downloadedUpdatePath, packageCopyPath, StandardCopyOption.REPLACE_EXISTING);

            String scriptName = "install-update-" + System.currentTimeMillis() + ".ps1";
            Path scriptPath = updaterDir.resolve(scriptName);
            Path appParent = appDir.getParent();
            if (appParent == null) {
                throw new IllegalStateException("Cannot determine app parent folder.");
            }

            String appFolderName = appDir.getFileName().toString();
            Path launcherTarget = appParent.resolve(appFolderName).resolve("MuaSamCong Downloader.exe");
            String launcherPath = launcherTarget.toString();
            Path resultFile = installResultFile();
            Path logFile = installLogFile();

            String script = String.join(System.lineSeparator(),
                "$ErrorActionPreference = \"Stop\"",
                "$zip = '" + toPsSingleQuoted(packageCopyPath.toString()) + "'",
                "$appParent = '" + toPsSingleQuoted(appParent.toString()) + "'",
                "$appFolderName = '" + toPsSingleQuoted(appFolderName) + "'",
                "$launcher = '" + toPsSingleQuoted(launcherPath) + "'",
                "$resultFile = '" + toPsSingleQuoted(resultFile.toString()) + "'",
                "$logFile = '" + toPsSingleQuoted(logFile.toString()) + "'",
                "if (Test-Path $resultFile) { Remove-Item $resultFile -Force }",
                "Start-Sleep -Seconds 2",
                "try {",
                "  \"[$(Get-Date -Format s)] Start install. zip=$zip\" | Out-File -FilePath $logFile -Encoding UTF8 -Append",
                "  Get-Process -Name 'MuaSamCong Downloader','java','javaw' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue",
                "  Start-Sleep -Seconds 1",
                "  $dest = Join-Path $appParent $appFolderName",
                "  $stage = Join-Path $appParent ('_update_staging_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())",
                "  if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }",
                "  Expand-Archive -Path $zip -DestinationPath $stage -Force",
                "  if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest -Force | Out-Null }",
                "  robocopy $stage $dest /MIR /R:5 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null",
                "  $rc = $LASTEXITCODE",
                "  if ($rc -ge 8) { throw \"robocopy failed with code $rc\" }",
                "  Remove-Item $stage -Recurse -Force",
                "  Get-ChildItem -Path $appParent -Directory | Where-Object { $_.Name -like 'MuaSamCong-Downloader-*-win' } | ForEach-Object {",
                "    try { Remove-Item $_.FullName -Recurse -Force } catch { \"[$(Get-Date -Format s)] Cleanup warning: $($_.FullName) :: $($_.Exception.Message)\" | Out-File -FilePath $logFile -Encoding UTF8 -Append }",
                "  }",
                "  \"SUCCESS|Installed $(Get-Date -Format s)\" | Out-File -FilePath $resultFile -Encoding UTF8 -Force",
                "  \"[$(Get-Date -Format s)] Install success\" | Out-File -FilePath $logFile -Encoding UTF8 -Append",
                "  Start-Process -FilePath $launcher",
                "} catch {",
                "  \"FAILED|$($_.Exception.Message)\" | Out-File -FilePath $resultFile -Encoding UTF8 -Force",
                "  \"[$(Get-Date -Format s)] Install failed: $($_.Exception.Message)\" | Out-File -FilePath $logFile -Encoding UTF8 -Append",
                "}"
            ) + System.lineSeparator();

            Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
            updateInstallProgressLabel.setText("Install: 50% - launching installer");

            new ProcessBuilder(
                "powershell",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptPath.toString()
            ).start();

            updateInstallProgressLabel.setText("Install: 75% - closing app");
            setUpdateStatus("Installing update and restarting app...", false);
            Platform.exit();
        } catch (Exception ex) {
            setUpdateStatus("Install failed: " + ex.getMessage(), true);
            updateInstallProgressLabel.setText("Install: failed");
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
        browserProfileComboBox.setDisable(disabled);
        saveBrowserProfileButton.setDisable(disabled);
        downloadUpdateButton.setDisable(disabled || latestUpdateInfo == null
            || !UpdateService.isNewerVersion(latestUpdateInfo.version(), AppInfo.VERSION));
        installUpdateButton.setDisable(disabled || downloadedUpdatePath == null || !Files.exists(downloadedUpdatePath));
    }

    private static String toPsSingleQuoted(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private Path installResultFile() {
        Path appDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path parent = appDir.getParent();
        if (parent == null) {
            return appDir.resolve("muasamcong-install-result.txt");
        }
        return parent.resolve("muasamcong-install-result.txt");
    }

    private Path installLogFile() {
        Path appDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path parent = appDir.getParent();
        if (parent == null) {
            return appDir.resolve("muasamcong-install.log");
        }
        return parent.resolve("muasamcong-install.log");
    }

    private void showInstallResultIfAny() {
        try {
            Path resultFile = installResultFile();
            if (!Files.exists(resultFile)) {
                return;
            }
            String result = Files.readString(resultFile, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
            Files.deleteIfExists(resultFile);
            if (result.regionMatches(true, 0, "SUCCESS|", 0, "SUCCESS|".length())) {
                Files.deleteIfExists(installLogFile());
                setUpdateStatus("Update installed successfully.", false);
                updateInstallProgressLabel.setText("Install: 100% - completed");
                return;
            }

            String reason = result.regionMatches(true, 0, "FAILED|", 0, "FAILED|".length())
                ? result.substring("FAILED|".length()).trim()
                : result;
            setUpdateStatus("Install failed: " + (reason.isBlank() ? "Unknown" : reason), true);
            updateInstallProgressLabel.setText("Install: failed");
            addDetailLog("Install log: " + installLogFile());
        } catch (Exception ex) {
            addDetailLog("Read install result failed: " + ex.getMessage());
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    private static String humanBytesSafe(Path file) {
        try {
            return humanBytes(Files.size(file));
        } catch (Exception ex) {
            return "unknown size";
        }
    }

    private void setUpdateStatus(String message, boolean error) {
        updateStatusLabel.setText(message == null ? "" : message);
        updateStatusLabel.getStyleClass().removeAll("field-hint", "status-error", "status-success");
        updateStatusLabel.getStyleClass().add(error ? "status-error" : "status-success");
    }

    private static String summarizeException(Throwable throwable) {
        if (throwable == null) {
            return "Unknown";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    private void handleExportGoogleSheet() {
        exportSheetButton.setDisable(true);
        updateStatus("Syncing sheet...", "status-running");
        addDetailLog("Exporting data to Google Sheets...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    BidSheetApiSyncService.refreshBidSheetRowsFromTracking();
                } catch (Exception ex) {
                    addDetailLog("Bid sheet refresh failed; syncing existing rows: " + summarizeException(ex));
                }
                if (!Files.exists(BID_ROWS_JSON_FILE)) {
                    throw new IllegalStateException("No data found to export: " + BID_ROWS_JSON_FILE);
                }
                addDetailLog("Uploading existing bid sheet rows to Google Sheets...");
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
                    || clearStateSearchText(record).contains(keywordFilter);
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
            List<RunStateStore.StateRecord> selectedRecords = new ArrayList<>();
            for (ClearStateItem item : stateItems) {
                if (item.selectedProperty().get() && item.record() != null) {
                    keys.add(item.record().key());
                    selectedRecords.add(item.record());
                }
            }

            if (keys.isEmpty()) {
                updateStatus("No keyword selected", "status-error");
                addDetailLog("Please check one or more keywords to delete from run state.");
                return;
            }

            int removed = RunStateStore.removeByKeys(keys);
            int removedTrackingRecords = BidTrackingRecordStore.removeByKeys(keys);
            int removedFingerprintRecords = ArtifactFingerprintStore.removeByKeys(keys);
            int removedRows = DownloadWorker.removeBidInfoRowsByStateRecords(selectedRecords);
            updateStatus("State removed: " + removed, "status-success");
            addDetailLog("Removed " + removed + " state records.");
            if (removedTrackingRecords > 0) {
                addDetailLog("Removed " + removedTrackingRecords + " bid tracking record(s).");
            }
            if (removedFingerprintRecords > 0) {
                addDetailLog("Removed " + removedFingerprintRecords + " artifact fingerprint record(s).");
            }
            if (removedRows > 0) {
                addDetailLog("Removed " + removedRows + " row(s) from bid_sheet_rows.json.");
                updateReportFromConfig();
            }
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
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                return;
            }

            try {
                RunStateStore.clear();
                RunHistoryStore.clear();
                BidTrackingRecordStore.clear();
                ArtifactFingerprintStore.clear();
                DownloadWorker.clearBidInfoOutput();
                updateReportFromConfig();
                clearRunData();
                refreshProgressView();
                updateStatus("Run state cleared", "status-success");
                addDetailLog("run_state.json, run_history, bid_tracking_records.json, artifact fingerprints and bid_sheet_rows.json were cleared.");
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

    private static String clearStateSearchText(RunStateStore.StateRecord record) {
        if (record == null) {
            return "";
        }
        return String.join(" ",
            safeSearchText(record.keyword()),
            safeSearchText(record.folderPath()),
            safeSearchText(folderDisplayPath(record.folderPath()))
        ).toLowerCase();
    }

    private static String safeSearchText(String value) {
        return value == null ? "" : value;
    }

    private static String folderDisplayPath(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return "[No folder]";
        }
        try {
            Path keywordFolder = Path.of(folderPath).toAbsolutePath().normalize();
            String keywordFolderName = keywordFolder.getFileName() == null
                ? keywordFolder.toString()
                : keywordFolder.getFileName().toString();
            String parentFolderName = keywordFolder.getParent() != null && keywordFolder.getParent().getFileName() != null
                ? keywordFolder.getParent().getFileName().toString()
                : null;

            String compactKeywordFolder = compactText(keywordFolderName, 46);
            if (parentFolderName == null || parentFolderName.isBlank()) {
                return "[" + compactKeywordFolder + "]";
            }
            return "[" + compactText(parentFolderName, 28) + " / " + compactKeywordFolder + "]";
        } catch (Exception ex) {
            return "[Invalid folder]";
        }
    }

    private static String compactText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(1, maxLength - 3)).trim() + "...";
    }

    private void showGoogleSheetsConfigDialog() {
        GoogleSheetsConfigStore.GoogleSheetsConfig current = GoogleSheetsConfigStore.load().normalized();

        CheckBox autoSyncCheck = new CheckBox("Enable auto sync to Google Sheets");
        boolean firstTimeConfig = (current.spreadsheetId() == null || current.spreadsheetId().isBlank())
            && (current.credentialsFile() == null || current.credentialsFile().isBlank());
        autoSyncCheck.setSelected(firstTimeConfig || current.autoSync());

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
                    showToast("Config invalid", true);
                    addDetailLog("Google Sheets config error: Spreadsheet ID is required when auto sync is enabled.");
                    return;
                }
                if (config.credentialsFile() == null || config.credentialsFile().isBlank()) {
                    showToast("Config invalid", true);
                    addDetailLog("Google Sheets config error: Credentials JSON path is required when auto sync is enabled.");
                    return;
                }
            }

            try {
                GoogleSheetsConfigStore.save(config);
                updateReportFromConfig();
                showToast("Config saved", false);
                addDetailLog("Google Sheets configuration saved.");
                dialog.close();
            } catch (Exception ex) {
                showToast("Save config failed", true);
                addDetailLog("Failed to save Google Sheets configuration: " + ex.getMessage());
            }
        });

        dialog.showAndWait();
    }

    private void finishDownloadTask() {
        activeDownloadTask = null;
        stopRequestedByUser = false;
        setDownloadRunningState(false);
        viewLogButton.setDisable(false);
        ExecutionGate.endDownload();
    }

    private void setDownloadRunningState(boolean running) {
        updateSharedUiState();
    }

    /** Update shared UI elements based on whether ANY task is running. */
    private void updateSharedUiState() {
        boolean downloadRunning = activeDownloadTask != null || ExecutionGate.isDownloadBusy();
        boolean monitorRunning = monitorScheduler != null && monitorScheduler.isCycleActive();
        boolean anyRunning = downloadRunning || monitorRunning;
        runningState = anyRunning;
        refreshMonitorActionButtons();
        folderListView.setDisable(anyRunning);
        addFolderButton.setDisable(anyRunning);
        deleteFolderButton.setDisable(anyRunning);
        checkAllFoldersButton.setDisable(anyRunning);
        uncheckAllFoldersButton.setDisable(anyRunning);
        exportSheetButton.setDisable(anyRunning);
        clearStateButton.setDisable(anyRunning);
        sheetsConfigButton.setDisable(anyRunning);
        browserProfileComboBox.setDisable(anyRunning);
        saveBrowserProfileButton.setDisable(anyRunning);
        monitorIntervalSpinner.setDisable(anyRunning);
        agentDownloadConcurrencySpinner.setDisable(anyRunning);
        if (seleniumDownloadConcurrencySpinner != null) {
            seleniumDownloadConcurrencySpinner.setDisable(anyRunning);
        }
        monitorSaveButton.setDisable(anyRunning);
        progressIndicator.setVisible(anyRunning);
        progressIndicator.setManaged(anyRunning);
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
            refreshMonitorActionButtons();
        });

        for (FolderItem item : folderItems) {
            registerFolderItemListener(item);
        }
        updateFolderSelectionLabel();
        refreshMonitorActionButtons();
    }

    private void registerFolderItemListener(FolderItem item) {
        if (item == null || folderSelectionListeners.containsKey(item)) {
            return;
        }
        ChangeListener<Boolean> listener = (obs, oldVal, newVal) -> {
            updateFolderSelectionLabel();
            refreshMonitorActionButtons();
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

    private void applyDownloadDisabledUi() {
        refreshMonitorActionButtons();
    }

    private void refreshMonitorActionButtons() {
        boolean hasSelection = countSelectedFolders() > 0;
        boolean monitorRunning = monitorScheduler != null && monitorScheduler.isCycleActive();
        boolean downloadRunning = activeDownloadTask != null || ExecutionGate.isDownloadBusy();
        boolean anyRunning = monitorRunning || downloadRunning;
        if (monitorRunNowButton != null) {
            monitorRunNowButton.setDisable(anyRunning || !hasSelection);
        }
        if (monitorStopButton != null) {
            monitorStopButton.setDisable(!anyRunning);
        }
    }

    private MonitorSettings buildMonitorSettingsFromUi() {
        return new MonitorSettings(
            true,
            monitorIntervalSpinner.getValue(),
            true,
            agentDownloadConcurrencySpinner.getValue(),
            bbmtSeleniumDownloadCheckBox != null && bbmtSeleniumDownloadCheckBox.isSelected(),
            seleniumDownloadConcurrencySpinner.getValue()
        ).normalized();
    }

    private void applyAgentDownloadConcurrency(int threads) {
        EgpAgentFileDownloadService.setMaxConcurrency(threads);
    }

    private void loadMonitorSettings() {
        try {
            MonitorSettings loaded = monitorScheduler.loadSettings().normalized();
            MonitorSettings settings = new MonitorSettings(
                true,
                loaded.intervalMinutes(),
                true,
                loaded.agentDownloadConcurrency(),
                loaded.bbmtSeleniumDownload(),
                loaded.seleniumDownloadConcurrency()
            ).normalized();
            monitorIntervalSpinner.getValueFactory().setValue(settings.intervalMinutes());
            agentDownloadConcurrencySpinner.getValueFactory().setValue(settings.agentDownloadConcurrency());
            seleniumDownloadConcurrencySpinner.getValueFactory().setValue(settings.seleniumDownloadConcurrency());
            if (bbmtSeleniumDownloadCheckBox != null) {
                bbmtSeleniumDownloadCheckBox.setSelected(settings.bbmtSeleniumDownload());
            }
            applyAgentDownloadConcurrency(settings.agentDownloadConcurrency());
            monitorScheduler.saveSettings(settings);
            monitorScheduler.start();
        } catch (Exception ex) {
            addDetailLog("Failed to load monitor settings: " + ex.getMessage());
        }
    }

    private void saveMonitorSettings() {
        try {
            MonitorSettings settings = buildMonitorSettingsFromUi();
            monitorIntervalSpinner.getValueFactory().setValue(settings.intervalMinutes());
            agentDownloadConcurrencySpinner.getValueFactory().setValue(settings.agentDownloadConcurrency());
            seleniumDownloadConcurrencySpinner.getValueFactory().setValue(settings.seleniumDownloadConcurrency());
            applyAgentDownloadConcurrency(settings.agentDownloadConcurrency());
            monitorScheduler.saveSettings(settings);
            showToast("Monitor settings saved", false);
            addDetailLog(
                "Monitor settings saved. Interval=" + settings.intervalMinutes()
                    + "m, agent threads=" + settings.agentDownloadConcurrency()
                    + ", Selenium Chrome=" + settings.seleniumDownloadConcurrency()
                    + ", BBMT Selenium=" + settings.bbmtSeleniumDownload() + "."
            );
        } catch (Exception ex) {
            showToast("Monitor save failed", true);
            addDetailLog("Unable to save monitor settings: " + ex.getMessage());
        }
    }

    private void loadBrowserProfileConfig() {
        try {
            BrowserProfileConfigStore.BrowserProfileConfig config = BrowserProfileConfigStore.load().normalized();
            if ("HIDDEN".equals(config.mode())) {
                browserProfileComboBox.setValue("Hidden (headless)");
            } else {
                browserProfileComboBox.setValue("Visible (Chrome window)");
            }
        } catch (Exception ex) {
            browserProfileComboBox.setValue("Visible (Chrome window)");
        }
    }

    private void handleSaveBrowserProfileConfig() {
        String selected = browserProfileComboBox.getValue();
        String mode;
        if ("Hidden (headless)".equals(selected)) {
            mode = "HIDDEN";
        } else {
            mode = "VISIBLE";
        }
        try {
            BrowserProfileConfigStore.save(new BrowserProfileConfigStore.BrowserProfileConfig(mode));
            showToast("Browser mode saved", false);
            addDetailLog("Browser mode saved: " + mode + ". New sessions will use this mode.");
        } catch (Exception ex) {
            showToast("Save failed", true);
            addDetailLog("Unable to save browser mode: " + ex.getMessage());
        }
    }

    private void ensureChromeProfilesForConcurrency(int concurrency) {
        int safeConcurrency = Math.max(1, Math.min(concurrency, 10));
        Path base = Utils.dataDirectory().resolve("chrome-profiles").toAbsolutePath().normalize();
        Utils.ensureDirectory(base);
        for (int i = 1; i <= safeConcurrency; i++) {
            Utils.ensureDirectory(base.resolve("profile-" + i));
        }
    }

    private List<Path> selectedPathsForDownload() {
        List<Path> result = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (FolderItem item : folderItems) {
            if (!item.selectedProperty().get()) {
                continue;
            }
            Path normalized = Path.of(item.path()).toAbsolutePath().normalize();
            String key = normalized.toString();
            if (!dedup.add(key)) {
                continue;
            }
            if (Files.exists(normalized) && Files.isDirectory(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private void exitApplication() {
        if (systemTraySupport != null) {
            systemTraySupport.dispose();
            systemTraySupport = null;
        }
        if (monitorScheduler != null) {
            monitorScheduler.requestStop();
        }
        if (ownerStage != null) {
            ownerStage.hide();
        }
        stop();
        Platform.exit();
    }

    @Override
    public void stop() {
        try {
            if (systemTraySupport != null) {
                systemTraySupport.dispose();
                systemTraySupport = null;
            }
            Utils.clearLogSink();
            if (activeDownloadTask != null) {
                Thread cleanup = new Thread(() -> {
                    try {
                        DownloadCoordinator.requestStop();
                    } catch (Throwable ignored) {
                        // Best effort shutdown only; never block JavaFX shutdown.
                    }
                }, "Downloader-Shutdown-Cleanup");
                cleanup.setDaemon(true);
                cleanup.start();
            }
        } catch (Throwable ignored) {
            // JavaFX shutdown must not fail because browser cleanup failed.
        }
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

        Thread pickerThread = new Thread(() -> {
            List<String> selectedPaths = List.of();
            Exception failure = null;
            try {
                selectedPaths = openMultipleDirectoriesWithSwing();
            } catch (Exception ex) {
                failure = ex;
            }
            List<String> paths = selectedPaths;
            Exception error = failure;
            Platform.runLater(() -> finishAddFolders(paths, error));
        }, "Folder-Picker");
        pickerThread.setDaemon(true);
        pickerThread.start();
    }

    private void finishAddFolders(List<String> selectedPaths, Exception failure) {
        try {
            if (failure != null) {
                updateStatus("Folder picker failed", "status-error");
                addDetailLog("Folder picker failed: " + failure.getMessage());
                return;
            }
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
    }

    private List<String> openMultipleDirectoriesWithSwing() {
        List<String> selectedPaths = new ArrayList<>();
        Runnable chooserTask = () -> {
            applySystemSwingLookAndFeel();
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Swing folder picker interrupted", ex);
        } catch (Exception ex) {
            throw new RuntimeException("Swing folder picker failed: " + ex.getMessage(), ex);
        }
        return selectedPaths;
    }

    private void applySystemSwingLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the default Swing look if the system look and feel is unavailable.
        }
    }

    private void setFolderActionsDisabled(boolean disabled) {
        addFolderButton.setDisable(disabled || runningState);
        deleteFolderButton.setDisable(disabled || runningState);
        checkAllFoldersButton.setDisable(disabled || runningState);
        uncheckAllFoldersButton.setDisable(disabled || runningState);
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
            List<FolderSelectionStore.FolderEntry> savedFolders = FolderSelectionStore.loadFolderEntries();
            for (FolderSelectionStore.FolderEntry entry : savedFolders) {
                folderItems.add(new FolderItem(entry.path(), entry.selected()));
            }
        } catch (Exception ex) {
            addDetailLog("Failed to load folder list: " + ex.getMessage());
        }
    }

    private void saveFolderSelections() {
        List<FolderSelectionStore.FolderEntry> entries = new ArrayList<>();
        for (FolderItem item : folderItems) {
            entries.add(new FolderSelectionStore.FolderEntry(item.path(), item.selectedProperty().get()));
        }
        FolderSelectionStore.saveFolderEntries(entries);
    }

    private List<Path> requireSelectedPaths() {
        List<String> selectedFolders = new ArrayList<>();
        for (FolderItem item : folderItems) {
            if (item.selectedProperty().get()) {
                selectedFolders.add(item.path());
            }
        }
        if (selectedFolders.isEmpty()) {
            throw new IllegalArgumentException("No folders selected for download run.");
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

    private void showToast(String text, boolean error) {
        if (text == null || text.isBlank() || toastLabel == null) {
            return;
        }
        Runnable show = () -> {
            toastLabel.setText(text);
            toastLabel.getStyleClass().removeAll("toast-success", "toast-error");
            toastLabel.getStyleClass().add(error ? "toast-error" : "toast-success");
            toastLabel.setVisible(true);
            toastLabel.setManaged(true);
            if (toastHideDelay != null) {
                toastHideDelay.stop();
            }
            toastHideDelay = new PauseTransition(Duration.seconds(error ? 4 : 3));
            toastHideDelay.setOnFinished(event -> {
                toastLabel.setVisible(false);
                toastLabel.setManaged(false);
            });
            toastHideDelay.playFromStart();
        };
        if (Platform.isFxApplicationThread()) {
            show.run();
        } else {
            Platform.runLater(show);
        }
    }

    private void handleRuntimeLog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            String normalized = line.trim();
            fullLogs.add(timestampedLog(normalized));
            updateProgressFromLog(normalized);
            refreshProgressView();
        });
    }

    private void updateProgressFromLog(String line) {
        Matcher loadedKeywordMatcher = LOADED_KEYWORD_PATTERN.matcher(line == null ? "" : line.trim());
        if (loadedKeywordMatcher.find()) {
            String keyword = loadedKeywordMatcher.group(1);
            String folder = loadedKeywordMatcher.group(2).trim();
            if (!keywordOrder.contains(keyword)) {
                keywordOrder.add(keyword);
            }
            keywordIndexState.putIfAbsent(keyword, keywordIndexState.size() + 1);
            keywordFolderState.put(keyword, folder);
            keywordResultState.putIfAbsent(keyword, "PENDING");
            keywordDownloadState.putIfAbsent(keyword, "-");
            return;
        }

        String keyword = extractKeyword(line);
        String status = extractRuntimeStatus(line);
        String runtimeMessage = extractRuntimeMessage(line);
        if (keyword != null && status != null) {
            currentActivityText = keyword + " [" + status + "]: "
                + messageForDisplay(runtimeMessage, "Working");
            String folderFromMessage = extractFolderFromMessage(runtimeMessage);
            if (folderFromMessage != null) {
                keywordFolderState.put(keyword, folderFromMessage);
            }
            updateAgentFileFromLog(keyword, runtimeMessage);
            updateBbmtFileFromLog(keyword, runtimeMessage);
        }
        if (keyword != null && "START".equals(status)) {
            moveKeywordToTop(keyword);
            keywordResultState.put(keyword, "RUNNING");
            keywordDownloadState.putIfAbsent(keyword, "-");
            keywordStartTimeState.put(keyword, extractRuntimeTime(line));
            keywordEndTimeState.put(keyword, "-");
            keywordMessageState.put(keyword, messageForDisplay(runtimeMessage, "Started"));
            failedKeywordSet.remove(keyword);
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

        if ("SUCCESS".equals(status)) {
            successCount++;
            if (keyword != null) {
                keywordResultState.put(keyword, "SUCCESS");
                keywordEndTimeState.put(keyword, extractRuntimeTime(line));
                keywordMessageState.put(keyword, messageForDisplay(runtimeMessage, "Completed"));
                failedKeywordSet.remove(keyword);
            }
            return;
        }

        if (keyword != null && line.contains("Downloaded files:")) {
            if (keywordAgentFilesState.containsKey(keyword)) {
                updateAgentFileProgress(keyword);
            } else {
                Matcher matcher = DOWNLOADED_FILES_PATTERN.matcher(line);
                if (matcher.find()) {
                    int downloaded = Integer.parseInt(matcher.group(1));
                    int expected = Integer.parseInt(matcher.group(2));
                    keywordDownloadState.put(keyword, downloaded + "/" + expected);
                }
            }
        }

        if (line.contains("Exceeded retry limit")) {
            if (keyword != null) {
                failedKeywords.add(keyword);
                failedKeywordSet.add(keyword);
                failCount = failedKeywordSet.size();
                keywordResultState.put(keyword, "FAILED");
                keywordEndTimeState.put(keyword, extractRuntimeTime(line));
                keywordMessageState.put(keyword, "Exceeded retry limit");
            }
            return;
        }

        if (keyword != null && "STOP".equals(status)) {
            keywordResultState.put(keyword, "STOPPED");
            keywordEndTimeState.put(keyword, extractRuntimeTime(line));
            keywordMessageState.put(keyword, messageForDisplay(runtimeMessage, "Stopped"));
        }

        if (keyword != null && !line.isBlank()) {
            keywordMessageState.put(keyword, messageForDisplay(runtimeMessage, keywordMessageState.get(keyword)));
        }
    }

    private void updateAgentFileFromLog(String keyword, String message) {
        if (keyword == null || keyword.isBlank() || message == null || !message.startsWith("Agent ")) {
            return;
        }
        String status = agentStatusFromMessage(message);
        if (status == null) {
            return;
        }
        Map<String, String> fields = agentFields(message);
        String fileName = valueOrDash(fields.get("file"));
        String fileId = valueOrDash(fields.get("fileId"));
        String folder = valueOrDash(fields.get("folder"));
        String key = !"-".equals(fileId) ? fileId : fileName;
        if (key == null || key.isBlank() || "-".equals(key)) {
            key = message;
        }

        LinkedHashMap<String, AgentFileProgressState> files = keywordAgentFilesState.computeIfAbsent(
            keyword,
            ignored -> new LinkedHashMap<>()
        );
        AgentFileProgressState previous = files.get(key);
        String effectiveFileName = "-".equals(fileName) && previous != null ? previous.fileName : fileName;
        String effectiveFileId = "-".equals(fileId) && previous != null ? previous.fileId : fileId;
        String effectiveFolder = "-".equals(folder) && previous != null ? previous.folder : folder;
        files.put(key, new AgentFileProgressState(
            effectiveFileName,
            effectiveFileId,
            status,
            agentMessageForDisplay(message),
            effectiveFolder,
            LocalTime.now().format(PROGRESS_TIME_FORMAT)
        ));
        currentAgentKeyword = keyword;
        updateAgentFileProgress(keyword);
    }

    private String agentStatusFromMessage(String message) {
        if (message.startsWith("Agent file found:")) {
            return "QUEUED";
        }
        if (message.startsWith("Agent downloading:")) {
            return "DOWNLOADING";
        }
        if (message.startsWith("Agent downloaded:")) {
            return "DOWNLOADED";
        }
        if (message.startsWith("Agent skipped existing:")) {
            return "SKIPPED_EXISTS";
        }
        if (message.startsWith("Agent skipped invalid ref:")) {
            return "FAILED";
        }
        if (message.startsWith("Agent unavailable:")) {
            return "AGENT_UNAVAILABLE";
        }
        if (message.startsWith("Agent failed 403:")) {
            return "FORBIDDEN";
        }
        if (message.startsWith("Agent failed:")) {
            return "FAILED";
        }
        return null;
    }

    private Map<String, String> agentFields(String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = AGENT_FILE_FIELD_PATTERN.matcher(message == null ? "" : message);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2).trim());
        }
        return fields;
    }

    private String agentMessageForDisplay(String message) {
        if (message == null || message.isBlank()) {
            return "-";
        }
        String[] parts = message.split("\\|");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.startsWith("file=")
                && !trimmed.startsWith("fileId=")
                && !trimmed.startsWith("folder=")
                && !trimmed.startsWith("Downloaded files:")
                && !trimmed.startsWith("completed=")) {
                return trimmed;
            }
        }
        return message;
    }

    private void updateAgentFileProgress(String keyword) {
        LinkedHashMap<String, AgentFileProgressState> files = keywordAgentFilesState.get(keyword);
        if (files == null || files.isEmpty()) {
            return;
        }
        int downloaded = 0;
        for (AgentFileProgressState file : files.values()) {
            if ("DOWNLOADED".equals(file.status) || "SKIPPED_EXISTS".equals(file.status)) {
                downloaded++;
            }
        }
        keywordDownloadState.put(keyword, downloaded + "/" + files.size());
    }

    private int countUnfinishedAgentFiles() {
        int unfinished = 0;
        for (LinkedHashMap<String, AgentFileProgressState> files : keywordAgentFilesState.values()) {
            if (files == null) {
                continue;
            }
            for (AgentFileProgressState file : files.values()) {
                if (!isAgentFileDone(file)) {
                    unfinished++;
                }
            }
        }
        return unfinished;
    }

    private boolean isAgentFileDone(AgentFileProgressState file) {
        return file != null
            && ("DOWNLOADED".equals(file.status) || "SKIPPED_EXISTS".equals(file.status));
    }

    private void updateBbmtFileFromLog(String keyword, String message) {
        if (keyword == null || keyword.isBlank() || message == null || message.isBlank()) {
            return;
        }
        if (message.startsWith("BBMT PDF saved:")) {
            String fileName = message.substring("BBMT PDF saved:".length()).split("\\|", 2)[0].trim();
            upsertBbmtFile(keyword, valueOrDash(fileName), "DOWNLOADED", message);
            return;
        }
        if (message.contains("BBMT already on disk:")) {
            String fileName = message.substring(message.indexOf("BBMT already on disk:") + "BBMT already on disk:".length()).trim();
            upsertBbmtFile(keyword, valueOrDash(fileName), "SKIPPED_EXISTS", message);
            return;
        }
        if (message.contains("BBMT tab not present")) {
            markExistingBbmtFile(keyword, "FAILED", message);
        }
    }

    private void upsertBbmtFile(String keyword, String fileName, String status, String message) {
        LinkedHashMap<String, AgentFileProgressState> files = keywordAgentFilesState.computeIfAbsent(
            keyword,
            ignored -> new LinkedHashMap<>()
        );
        String key = firstBbmtFileKey(files);
        AgentFileProgressState previous = key == null ? null : files.get(key);
        String effectiveKey = key == null ? "BBMT:" + fileName : key;
        files.put(effectiveKey, new AgentFileProgressState(
            valueOrDash(fileName),
            previous == null ? "-" : previous.fileId,
            status,
            message,
            previous == null ? "-" : previous.folder,
            LocalTime.now().format(PROGRESS_TIME_FORMAT)
        ));
        currentAgentKeyword = keyword;
        updateAgentFileProgress(keyword);
    }

    private void markExistingBbmtFile(String keyword, String status, String message) {
        LinkedHashMap<String, AgentFileProgressState> files = keywordAgentFilesState.get(keyword);
        String key = firstBbmtFileKey(files);
        if (key == null || files == null) {
            return;
        }
        AgentFileProgressState previous = files.get(key);
        files.put(key, new AgentFileProgressState(
            previous.fileName,
            previous.fileId,
            status,
            message,
            previous.folder,
            LocalTime.now().format(PROGRESS_TIME_FORMAT)
        ));
        currentAgentKeyword = keyword;
        updateAgentFileProgress(keyword);
    }

    private String firstBbmtFileKey(LinkedHashMap<String, AgentFileProgressState> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, AgentFileProgressState> entry : files.entrySet()) {
            AgentFileProgressState file = entry.getValue();
            if (file != null && looksLikeBbmtName(file.fileName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean looksLikeBbmtName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String lower = fileName.toLowerCase();
        String folded = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return folded.contains("bbmt")
            || folded.contains("bien ban")
            || folded.contains("mo thau")
            || folded.contains("contractor_list");
    }

    private void refreshProgressView() {
        loadedLabel.setText("Loaded: " + loadedKeywords);
        runningLabel.setText("Running: " + countRowsWithStatus("RUNNING"));
        downloadedFilesLabel.setText("Files: " + totalDownloadedFilesText());
        successLabel.setText("Success: " + successCount);
        failCount = failedKeywordSet.size();
        failedLabel.setText("Failed: " + failCount);
        currentActivityLabel.setText("Now: " + currentActivityText);
        List<KeywordProgressRow> rows = buildKeywordProgressRows();
        List<String> mainSelectedKeywords = selectedKeywords(progressTableView);
        int mainSelectedIndex = progressTableView == null ? -1 : progressTableView.getSelectionModel().getSelectedIndex();
        List<String> detailSelectedKeywords = selectedKeywords(detailProgressTableView);
        int detailSelectedIndex = detailProgressTableView == null ? -1 : detailProgressTableView.getSelectionModel().getSelectedIndex();

        progressRows.setAll(rows);

        restoreProgressSelection(progressTableView, mainSelectedKeywords, mainSelectedIndex);
        restoreProgressSelection(detailProgressTableView, detailSelectedKeywords, detailSelectedIndex);
        refreshAgentFileDetail();
        refreshDetailAgentFileDetail();
    }

    private void clearRunData() {
        fullLogs.clear();
        failedKeywords.clear();
        failedKeywordSet.clear();
        keywordOrder.clear();
        keywordIndexState.clear();
        keywordFolderState.clear();
        keywordDownloadState.clear();
        keywordResultState.clear();
        keywordMessageState.clear();
        keywordStartTimeState.clear();
        keywordEndTimeState.clear();
        keywordAgentFilesState.clear();
        agentFileRows.clear();
        detailAgentFileRows.clear();
        selectedAgentKeyword = null;
        currentAgentKeyword = null;
        currentActivityText = "Idle";
        resetProgressCounters();
        viewLogButton.setDisable(false);
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
        Platform.runLater(() -> {
            String normalized = line.trim();
            fullLogs.add(timestampedLog(normalized));
        });
    }

    private String timestampedLog(String line) {
        return LocalDateTime.now().format(LOG_TIME_FORMAT) + " " + line;
    }

    private void showLogDetails() {
        Path logFile = appLogFile();
        if (!Files.exists(logFile)) {
            updateStatus("Log not found", "status-error");
            addDetailLog("App log file not found: " + logFile);
            return;
        }

        try {
            Desktop.getDesktop().open(logFile.toFile());
        } catch (Exception ex) {
            updateStatus("Open log failed", "status-error");
            addDetailLog("Unable to open app log: " + ex.getMessage());
        }
    }

    private Path appLogFile() {
        return Path.of("logs", "app-" + LocalDate.now() + ".log").toAbsolutePath().normalize();
    }

    private void setProgressZoomPercent(int requestedPercent) {
        progressZoomPercent = Math.max(100, Math.min(150, requestedPercent));
        if (detailProgressZoomLabel != null) {
            detailProgressZoomLabel.setText(progressZoomPercent + "%");
        }
        if (detailProgressRoot != null) {
            detailProgressRoot.getStyleClass().removeAll(
                "progress-zoom-100",
                "progress-zoom-125",
                "progress-zoom-150"
            );
            detailProgressRoot.getStyleClass().add("progress-zoom-" + progressZoomPercent);
        }
        double rowHeight = switch (progressZoomPercent) {
            case 150 -> 48;
            case 125 -> 42;
            default -> 34;
        };
        if (detailProgressTableView != null) {
            detailProgressTableView.setFixedCellSize(rowHeight);
        }
        if (detailAgentFileTableView != null) {
            detailAgentFileTableView.setFixedCellSize(rowHeight);
        }
    }

    private void showProgressFullscreen() {
        if (detailProgressStage != null && detailProgressStage.isShowing()) {
            detailProgressStage.toFront();
            detailProgressStage.requestFocus();
            return;
        }
        TableView<KeywordProgressRow> detailTableView = createProgressTable(true);
        detailProgressTableView = detailTableView;
        detailAgentFileTableView = createAgentFileTable(detailAgentFileRows);
        TableView<AgentFileProgressRow> detailFileTableView = detailAgentFileTableView;
        detailFileTableView.setPrefHeight(240);
        detailFileTableView.setMinHeight(180);

        detailTableView.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            selectedAgentKeyword = newRow == null ? null : newRow.keyword();
            if (selectedAgentKeyword != null && !selectedAgentKeyword.isBlank()) {
                selectKeywordInTable(progressTableView, selectedAgentKeyword);
            }
            refreshAgentFileDetail();
            refreshDetailAgentFileDetail();
        });

        detailAgentFileTitleLabel = new Label("Files: select a keyword");
        detailAgentFileTitleLabel.getStyleClass().add("section-title-small");
        detailAgentFileFolderLabel = new Label("Folder: -");
        detailAgentFileFolderLabel.getStyleClass().add("field-hint");
        VBox detailAgentFilePanel = buildAgentFilePanel(
            detailAgentFileTitleLabel,
            detailAgentFileFolderLabel,
            detailFileTableView,
            true
        );

        Button zoomOutButton = new Button("-");
        zoomOutButton.getStyleClass().add("secondary-button");
        Button zoomInButton = new Button("+");
        zoomInButton.getStyleClass().add("secondary-button");
        Button resetZoomButton = new Button("Reset");
        resetZoomButton.getStyleClass().add("secondary-button");
        detailProgressZoomLabel = new Label();
        detailProgressZoomLabel.getStyleClass().add("field-hint");

        zoomOutButton.setOnAction(event -> setProgressZoomPercent(progressZoomPercent - 25));
        zoomInButton.setOnAction(event -> setProgressZoomPercent(progressZoomPercent + 25));
        resetZoomButton.setOnAction(event -> setProgressZoomPercent(125));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, new Label("Zoom"), zoomOutButton, detailProgressZoomLabel, zoomInButton, resetZoomButton, spacer);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        SplitPane detailSplit = new SplitPane(detailTableView, detailAgentFilePanel);
        detailSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        detailSplit.setDividerPositions(0.67);

        VBox content = new VBox(10, toolbar, detailSplit);
        content.getStyleClass().add("progress-zoom-root");
        content.setPadding(new Insets(12));
        VBox.setVgrow(detailTableView, Priority.ALWAYS);
        VBox.setVgrow(detailSplit, Priority.ALWAYS);
        detailProgressRoot = content;
        setProgressZoomPercent(progressZoomPercent);
        restoreProgressSelection(detailTableView, selectedKeywords(progressTableView), progressTableView.getSelectionModel().getSelectedIndex());
        refreshDetailAgentFileDetail();

        Scene detailScene = new Scene(content, 1180, 760);
        Stage detailStage = new Stage();
        detailStage.initOwner(ownerStage);
        detailStage.setTitle("Progress zoom");
        detailStage.setScene(detailScene);
        detailStage.setResizable(true);
        detailProgressStage = detailStage;
        detailStage.setOnHidden(event -> {
            if (detailProgressTableView == detailTableView) {
                detailProgressTableView = null;
            }
            if (detailAgentFileTableView == detailFileTableView) {
                detailAgentFileTableView = null;
                detailAgentFileRows.clear();
            }
            detailAgentFileTitleLabel = null;
            detailAgentFileFolderLabel = null;
            detailProgressZoomLabel = null;
            detailProgressRoot = null;
            if (detailProgressStage == detailStage) {
                detailProgressStage = null;
            }
        });
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
        List<KeywordProgressRow> rows = buildKeywordProgressRows();
        if (rows.isEmpty()) {
            return "No keyword progress yet.";
        }

        StringBuilder builder = new StringBuilder();
        for (KeywordProgressRow row : rows) {
            builder.append(String.format("[%03d] %s  %s  Downloaded files: %s",
                    row.index(), row.keyword(), row.status(), row.downloaded()))
                .append(System.lineSeparator())
                .append("      Folder: ")
                .append(row.folder())
                .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private List<KeywordProgressRow> buildKeywordProgressRows() {
        List<String> displayOrder = keywordOrder.isEmpty()
            ? new ArrayList<>(keywordResultState.keySet())
            : keywordOrder;
        List<KeywordProgressRow> rows = new ArrayList<>();
        for (String keyword : displayOrder) {
            rows.add(new KeywordProgressRow(
                keywordIndexState.getOrDefault(keyword, 0),
                keyword,
                keywordResultState.getOrDefault(keyword, "PENDING"),
                keywordDownloadState.getOrDefault(keyword, "-"),
                keywordFolderState.getOrDefault(keyword, "-"),
                keywordMessageState.getOrDefault(keyword, "-"),
                keywordStartTimeState.getOrDefault(keyword, "-"),
                keywordEndTimeState.getOrDefault(keyword, "-")
            ));
        }
        return rows;
    }

    private TableView<KeywordProgressRow> createProgressTable(boolean detailMode) {
        TableView<KeywordProgressRow> tableView = new TableView<>(progressRows);
        tableView.getStyleClass().add("progress-table-view");
        if (detailMode) {
            tableView.getStyleClass().add("progress-table-view-detail");
        }
        tableView.setPlaceholder(new Label("No keyword progress yet."));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<KeywordProgressRow, String> keywordCol = new TableColumn<>("Keyword");
        keywordCol.setCellValueFactory(cell -> cell.getValue().keywordDisplayProperty());
        keywordCol.setMinWidth(210);

        TableColumn<KeywordProgressRow, String> folderCol = new TableColumn<>("Folder");
        folderCol.setCellValueFactory(cell -> cell.getValue().folderProperty());
        folderCol.setMinWidth(220);

        TableColumn<KeywordProgressRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(cell -> cell.getValue().statusProperty());
        statusCol.setMinWidth(95);
        statusCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTextFill(Color.web("#0f172a"));
                    setStyle("");
                    return;
                }
                setText(item);
                setTextFill(statusColor(item));
                setStyle("-fx-text-fill: " + statusColorHex(item) + "; -fx-font-weight: 700;");
            }
        });

        TableColumn<KeywordProgressRow, String> progressCol = new TableColumn<>("Files");
        progressCol.setCellValueFactory(cell -> cell.getValue().downloadedProperty());
        progressCol.setMinWidth(110);

        TableColumn<KeywordProgressRow, String> messageCol = new TableColumn<>("Message");
        messageCol.setCellValueFactory(cell -> cell.getValue().messageProperty());
        messageCol.setMinWidth(190);

        TableColumn<KeywordProgressRow, String> startCol = new TableColumn<>("Start time");
        startCol.setCellValueFactory(cell -> cell.getValue().startTimeProperty());
        startCol.setMinWidth(120);

        TableColumn<KeywordProgressRow, String> endCol = new TableColumn<>("End time");
        endCol.setCellValueFactory(cell -> cell.getValue().endTimeProperty());
        endCol.setMinWidth(120);

        tableView.getColumns().setAll(keywordCol, folderCol, statusCol, progressCol, messageCol, startCol, endCol);
        tableView.setColumnResizePolicy(detailMode
            ? TableView.UNCONSTRAINED_RESIZE_POLICY
            : TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        attachProgressCopyMenu(tableView);
        return tableView;
    }

    private TableView<AgentFileProgressRow> createAgentFileTable(ObservableList<AgentFileProgressRow> rows) {
        TableView<AgentFileProgressRow> tableView = new TableView<>(rows);
        tableView.getStyleClass().add("agent-file-table-view");
        tableView.setPlaceholder(new Label("No files detected for this keyword yet."));
        tableView.setPrefHeight(165);
        tableView.setMinHeight(130);

        TableColumn<AgentFileProgressRow, String> fileCol = new TableColumn<>("File");
        fileCol.setCellValueFactory(cell -> cell.getValue().fileNameProperty());
        fileCol.setMinWidth(260);

        TableColumn<AgentFileProgressRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(cell -> cell.getValue().statusProperty());
        statusCol.setMinWidth(135);
        statusCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                setStyle("-fx-text-fill: " + agentStatusColorHex(item) + "; -fx-font-weight: 700;");
            }
        });

        TableColumn<AgentFileProgressRow, String> messageCol = new TableColumn<>("Message");
        messageCol.setCellValueFactory(cell -> cell.getValue().messageProperty());
        messageCol.setMinWidth(230);

        TableColumn<AgentFileProgressRow, String> fileIdCol = new TableColumn<>("File ID");
        fileIdCol.setCellValueFactory(cell -> cell.getValue().fileIdProperty());
        fileIdCol.setMinWidth(180);

        TableColumn<AgentFileProgressRow, String> updatedCol = new TableColumn<>("Updated");
        updatedCol.setCellValueFactory(cell -> cell.getValue().updatedAtProperty());
        updatedCol.setMinWidth(90);

        tableView.getColumns().setAll(fileCol, statusCol, messageCol, fileIdCol, updatedCol);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return tableView;
    }

    private void refreshAgentFileDetail() {
        refreshAgentFileDetail(agentFileRows, agentFileTitleLabel, agentFileFolderLabel);
    }

    private void refreshDetailAgentFileDetail() {
        if (detailAgentFileTableView == null) {
            return;
        }
        refreshAgentFileDetail(detailAgentFileRows, detailAgentFileTitleLabel, detailAgentFileFolderLabel);
    }

    private void refreshAgentFileDetail(
        ObservableList<AgentFileProgressRow> rowsTarget,
        Label titleLabel,
        Label folderLabel
    ) {
        String keyword = selectedAgentKeyword != null && !selectedAgentKeyword.isBlank()
            ? selectedAgentKeyword
            : currentAgentKeyword;
        if (titleLabel != null) {
            titleLabel.setText(keyword == null || keyword.isBlank() ? "Files: select a keyword" : "Files: " + keyword);
        }
        if (folderLabel != null) {
            String folder = keyword == null ? null : keywordFolderState.get(keyword);
            String downloadFolder = agentDownloadFolder(keyword);
            folderLabel.setText("Folder: " + valueOrDash(folder) + " | Download: " + valueOrDash(downloadFolder));
        }
        if (keyword == null || keyword.isBlank()) {
            rowsTarget.clear();
            return;
        }
        LinkedHashMap<String, AgentFileProgressState> files = keywordAgentFilesState.get(keyword);
        if (files == null || files.isEmpty()) {
            rowsTarget.clear();
            return;
        }
        List<AgentFileProgressRow> rows = new ArrayList<>();
        for (AgentFileProgressState file : files.values()) {
            rows.add(new AgentFileProgressRow(
                valueOrDash(file.fileName),
                valueOrDash(file.fileId),
                valueOrDash(file.status),
                valueOrDash(file.message),
                valueOrDash(file.folder),
                valueOrDash(file.updatedAt)
            ));
        }
        rowsTarget.setAll(rows);
    }

    private String agentDownloadFolder(String keyword) {
        LinkedHashMap<String, AgentFileProgressState> files = keyword == null ? null : keywordAgentFilesState.get(keyword);
        if (files == null || files.isEmpty()) {
            return null;
        }
        for (AgentFileProgressState file : files.values()) {
            if (file != null && file.folder != null && !file.folder.isBlank() && !"-".equals(file.folder)) {
                return file.folder;
            }
        }
        return null;
    }

    private void attachProgressCopyMenu(TableView<KeywordProgressRow> tableView) {
        MenuItem copySelectedItem = new MenuItem("Copy selected");
        copySelectedItem.setOnAction(event -> {
            List<KeywordProgressRow> selectedRows = new ArrayList<>(tableView.getSelectionModel().getSelectedItems());
            copyProgressRowsToClipboard(selectedRows);
        });

        MenuItem copyAllItem = new MenuItem("Copy all progress");
        copyAllItem.setOnAction(event -> copyProgressRowsToClipboard(new ArrayList<>(tableView.getItems())));

        ContextMenu contextMenu = new ContextMenu(copySelectedItem, copyAllItem);
        contextMenu.setOnShowing(event -> copySelectedItem.setDisable(tableView.getSelectionModel().getSelectedItems().isEmpty()));
        tableView.setContextMenu(contextMenu);
    }

    private void copyProgressRowsToClipboard(List<KeywordProgressRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        for (KeywordProgressRow row : rows) {
            builder.append(formatProgressRowForCopy(row)).append(System.lineSeparator()).append(System.lineSeparator());
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(builder.toString().trim());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private List<String> selectedKeywords(TableView<KeywordProgressRow> tableView) {
        List<String> selectedKeywords = new ArrayList<>();
        if (tableView == null) {
            return selectedKeywords;
        }
        for (KeywordProgressRow row : tableView.getSelectionModel().getSelectedItems()) {
            if (row != null && row.keyword() != null) {
                selectedKeywords.add(row.keyword());
            }
        }
        return selectedKeywords;
    }

    private void restoreProgressSelection(TableView<KeywordProgressRow> tableView, List<String> selectedKeywords, int selectedIndex) {
        if (tableView == null) {
            return;
        }

        if (selectedKeywords.isEmpty()) {
            return;
        }

        tableView.getSelectionModel().clearSelection();
        for (int i = 0; i < progressRows.size(); i++) {
            KeywordProgressRow row = progressRows.get(i);
            if (row != null && selectedKeywords.contains(row.keyword())) {
                tableView.getSelectionModel().select(i);
            }
        }

        if (!tableView.getSelectionModel().getSelectedItems().isEmpty()) {
            tableView.scrollTo(Math.max(0, tableView.getSelectionModel().getSelectedIndex()));
        } else if (selectedIndex >= 0 && selectedIndex < progressRows.size()) {
            tableView.scrollTo(selectedIndex);
        }
    }

    private void selectKeywordInTable(TableView<KeywordProgressRow> tableView, String keyword) {
        if (tableView == null || keyword == null || keyword.isBlank()) {
            return;
        }
        KeywordProgressRow selected = tableView.getSelectionModel().getSelectedItem();
        if (selected != null && keyword.equals(selected.keyword())) {
            return;
        }
        for (int i = 0; i < progressRows.size(); i++) {
            KeywordProgressRow row = progressRows.get(i);
            if (row != null && keyword.equals(row.keyword())) {
                tableView.getSelectionModel().clearAndSelect(i);
                tableView.scrollTo(i);
                return;
            }
        }
    }

    private String formatProgressRowForCopy(KeywordProgressRow row) {
        if (row == null) {
            return "";
        }
        return String.format("[%03d] %s  %s  Progress: %s%s      Folder: %s%s      Message: %s%s      Start: %s | End: %s",
            row.index(),
            row.keyword(),
            row.status(),
            row.downloaded(),
            System.lineSeparator(),
            row.folder(),
            System.lineSeparator(),
            row.message(),
            System.lineSeparator(),
            row.startTime(),
            row.endTime()
        );
    }

    private void moveKeywordToTop(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        keywordIndexState.putIfAbsent(keyword, keywordIndexState.size() + 1);
        keywordOrder.remove(keyword);
        keywordOrder.add(0, keyword);
    }

    private String extractKeyword(String line) {
        Matcher runtimeMatcher = RUNTIME_LOG_PATTERN.matcher(line == null ? "" : line.trim());
        if (runtimeMatcher.find()) {
            return runtimeMatcher.group(1);
        }
        Matcher matcher = KEYWORD_PATTERN.matcher(line == null ? "" : line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractRuntimeStatus(String line) {
        Matcher matcher = RUNTIME_LOG_PATTERN.matcher(line == null ? "" : line.trim());
        return matcher.find() ? matcher.group(2) : null;
    }

    private String extractRuntimeTime(String line) {
        String normalized = line == null ? "" : line.trim();
        Matcher fullDateTime = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2})").matcher(normalized);
        if (fullDateTime.find()) {
            return fullDateTime.group(1).replace('T', ' ');
        }

        Matcher timeOnly = Pattern.compile("^(\\d{2}:\\d{2}:\\d{2})").matcher(normalized);
        if (timeOnly.find()) {
            return timeOnly.group(1);
        }

        return LocalTime.now().format(PROGRESS_TIME_FORMAT);
    }

    private String extractRuntimeMessage(String line) {
        Matcher matcher = RUNTIME_LOG_PATTERN.matcher(line == null ? "" : line.trim());
        return matcher.find() ? matcher.group(3) : "";
    }

    private String extractFolderFromMessage(String message) {
        Matcher matcher = FOLDER_IN_MESSAGE_PATTERN.matcher(message == null ? "" : message);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String messageForDisplay(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "-";
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private int countRowsWithStatus(String status) {
        int count = 0;
        for (String value : keywordResultState.values()) {
            if (status.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private String totalDownloadedFilesText() {
        int downloaded = 0;
        int expected = 0;
        boolean hasExpected = false;
        for (String value : keywordDownloadState.values()) {
            Matcher matcher = DOWNLOADED_FILES_PATTERN.matcher("Downloaded files: " + value);
            if (!matcher.find()) {
                continue;
            }
            downloaded += Integer.parseInt(matcher.group(1));
            expected += Integer.parseInt(matcher.group(2));
            hasExpected = true;
        }
        return hasExpected ? downloaded + "/" + expected : "0/0";
    }

    private record KeywordProgressRow(
        int index,
        String keyword,
        String status,
        String downloaded,
        String folder,
        String message,
        String startTime,
        String endTime
    ) {
        private javafx.beans.property.SimpleStringProperty keywordDisplayProperty() {
            return new javafx.beans.property.SimpleStringProperty(String.format("[%03d] %s", index, keyword));
        }

        private javafx.beans.property.SimpleStringProperty statusProperty() {
            return new javafx.beans.property.SimpleStringProperty(status);
        }

        private javafx.beans.property.SimpleStringProperty downloadedProperty() {
            return new javafx.beans.property.SimpleStringProperty(downloaded);
        }

        private javafx.beans.property.SimpleStringProperty folderProperty() {
            return new javafx.beans.property.SimpleStringProperty(folder);
        }

        private javafx.beans.property.SimpleStringProperty messageProperty() {
            return new javafx.beans.property.SimpleStringProperty(message);
        }

        private javafx.beans.property.SimpleStringProperty startTimeProperty() {
            return new javafx.beans.property.SimpleStringProperty(startTime);
        }

        private javafx.beans.property.SimpleStringProperty endTimeProperty() {
            return new javafx.beans.property.SimpleStringProperty(endTime);
        }
    }

    private record AgentFileProgressState(
        String fileName,
        String fileId,
        String status,
        String message,
        String folder,
        String updatedAt
    ) {
    }

    private record AgentFileProgressRow(
        String fileName,
        String fileId,
        String status,
        String message,
        String folder,
        String updatedAt
    ) {
        private javafx.beans.property.SimpleStringProperty fileNameProperty() {
            return new javafx.beans.property.SimpleStringProperty(fileName);
        }

        private javafx.beans.property.SimpleStringProperty fileIdProperty() {
            return new javafx.beans.property.SimpleStringProperty(fileId);
        }

        private javafx.beans.property.SimpleStringProperty statusProperty() {
            return new javafx.beans.property.SimpleStringProperty(status);
        }

        private javafx.beans.property.SimpleStringProperty messageProperty() {
            return new javafx.beans.property.SimpleStringProperty(message);
        }

        private javafx.beans.property.SimpleStringProperty updatedAtProperty() {
            return new javafx.beans.property.SimpleStringProperty(updatedAt);
        }
    }

    private static Color statusColor(String status) {
        return switch (status == null ? "" : status) {
            case "RUNNING" -> Color.web("#2563eb");
            case "SUCCESS" -> Color.web("#16a34a");
            case "FAILED" -> Color.web("#dc2626");
            case "STOPPED" -> Color.web("#d97706");
            default -> Color.web("#64748b");
        };
    }

    private static String statusColorHex(String status) {
        return switch (status == null ? "" : status) {
            case "RUNNING" -> "#2563eb";
            case "SUCCESS" -> "#16a34a";
            case "FAILED" -> "#dc2626";
            case "STOPPED" -> "#d97706";
            default -> "#64748b";
        };
    }

    private static String agentStatusColorHex(String status) {
        return switch (status == null ? "" : status) {
            case "DOWNLOADED", "SKIPPED_EXISTS" -> "#16a34a";
            case "DOWNLOADING" -> "#2563eb";
            case "QUEUED" -> "#6d28d9";
            case "FORBIDDEN", "FAILED", "AGENT_UNAVAILABLE" -> "#dc2626";
            default -> "#64748b";
        };
    }

    private static final class FolderItem {
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private String path;

        private FolderItem(String path) {
            this(path, true);
        }

        private FolderItem(String path, boolean selected) {
            this.path = path;
            this.selected.set(selected);
        }

        private String path() {
            return path;
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
        private final Label keywordLabel = new Label();
        private final Label folderLabel = new Label();
        private final HBox content = new HBox(10, checkBox, keywordLabel, folderLabel);
        private ClearStateItem bound;

        private ClearStateItemCell() {
            content.setAlignment(Pos.CENTER_LEFT);
            keywordLabel.setTextFill(Color.web("#0B5CAD"));
            keywordLabel.setFont(Font.font(keywordLabel.getFont().getFamily(), FontWeight.BOLD, keywordLabel.getFont().getSize()));
            folderLabel.setTextFill(Color.web("#6B7280"));
            folderLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(folderLabel, Priority.ALWAYS);
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
            keywordLabel.setText(valueOrDash(record == null ? null : record.keyword()));
            folderLabel.setText(record == null ? "[No folder]" : folderDisplayPath(record.folderPath()));
            setTooltip(record == null || record.folderPath() == null || record.folderPath().isBlank()
                ? null
                : new Tooltip(record.folderPath()));
            setGraphic(content);
        }

        private String valueOrDash(String value) {
            return (value == null || value.isBlank()) ? "-" : value;
        }
    }

    public static void main(String[] args) {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            System.setProperty("java.awt.headless", "false");
        }
        launch(args);
    }
}
