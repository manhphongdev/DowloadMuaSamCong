package vn.muasamcong.downloader.app;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.stage.Stage;
import vn.muasamcong.downloader.util.Utils;

final class SystemTraySupport {

    private final Stage stage;
    private final Runnable onExit;
    private final Consumer<String> log;
    private TrayIcon trayIcon;

    private SystemTraySupport(Stage stage, Runnable onExit, Consumer<String> log) {
        this.stage = stage;
        this.onExit = onExit;
        this.log = log == null ? message -> { } : log;
    }

    static SystemTraySupport install(Stage stage, Runnable onExit, Consumer<String> log) {
        if (stage == null || !SystemTray.isSupported()) {
            if (log != null) {
                log.accept("System tray is not supported on this platform.");
            }
            return null;
        }
        try {
            SystemTraySupport support = new SystemTraySupport(stage, onExit, log);
            support.installTrayIcon();
            return support;
        } catch (AWTException | RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (log != null) {
                log.accept("Unable to install system tray: " + message);
            }
            Utils.logPlain("Unable to install system tray: " + message);
            return null;
        }
    }

    void attachCloseToTray() {
        stage.setOnCloseRequest(event -> {
            event.consume();
            hideToTray();
        });
    }

    void hideToTray() {
        if (trayIcon == null) {
            return;
        }
        Platform.runLater(() -> {
            if (stage.isShowing()) {
                stage.hide();
            }
            log.accept("App is running in the system tray. Double-click the icon to reopen.");
        });
    }

    void showFromTray() {
        Platform.runLater(() -> {
            if (!stage.isShowing()) {
                stage.show();
            }
            stage.toFront();
            stage.requestFocus();
        });
    }

    void dispose() {
        if (trayIcon == null) {
            return;
        }
        SystemTray tray = SystemTray.getSystemTray();
        tray.remove(trayIcon);
        trayIcon = null;
    }

    private void installTrayIcon() throws AWTException {
        if (trayIcon != null) {
            return;
        }
        TrayIcon icon = new TrayIcon(createTrayImage(), AppInfo.APP_NAME);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> showFromTray());

        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("Mở cửa sổ");
        openItem.addActionListener(event -> showFromTray());
        menu.add(openItem);

        MenuItem exitItem = new MenuItem("Thoát");
        exitItem.addActionListener(event -> Platform.runLater(onExit));
        menu.add(exitItem);

        icon.setPopupMenu(menu);
        SystemTray.getSystemTray().add(icon);
        trayIcon = icon;
        log.accept("System tray enabled. Closing the window keeps the app running in the background.");
    }

    private static BufferedImage createTrayImage() {
        int size = 16;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x1A5FB4));
            graphics.fillRoundRect(0, 0, size, size, 4, 4);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            graphics.drawString("M", 4, 13);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
